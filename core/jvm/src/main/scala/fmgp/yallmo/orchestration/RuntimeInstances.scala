package fmgp.yallmo.orchestration

import fmgp.yallmo.agents.{CoachAgent, FolderStructureAgent}
import fmgp.yallmo.fs.{FileSystem, FsRead}
import fmgp.yallmo.model.{Agent, AgentId, AudioAgent, YallmoError}
import fmgp.yallmo.model.llm.LlmProvider
import fmgp.yallmo.model.som.SOMProvider
import fmgp.yallmo.orchestration.config.{AgentConfig, Config, OAuthCredentialsConfig, ProviderConfig, ProviderId}
import fmgp.yallmo.provider.oai.{OAIAgent, OAIConfig, OAILlmProvider}
import fmgp.yallmo.provider.openai.{ChatGPTAgent, OpenAiCodexClient}
import zio.*
import zio.http.Client

import java.nio.file.Path

/** Owns lazy runtime instances for one configuration snapshot with explicit ChatGPT credential replacement.
  *
  * Successful lookups are cached and concurrent initialization is serialized. Already-created agents retain their
  * identity and environment requirements; configured agents have their dependencies bound at initialization. Neither
  * construction nor lookup makes network requests or reads environment variables. Folder-agent lookup validates its
  * configured filesystem root after resolving its symbolic links, but does not review it or write state. Failures are
  * not cached.
  *
  * The captured HTTP client must outlive this registry. Reusing a Config with a new registry creates independent
  * caches; arbitrary configuration edits do not mutate existing instances. Explicit credential replacement publishes a
  * new connection for future lookups; callers already holding an agent/provider retain the previous generation.
  * Automatic ChatGPT refresh stays in connection-local memory.
  *
  * @tparam R
  *   per-turn environment required by caller-supplied agents; configured agents require no extra services
  * @param config
  *   immutable definitions used to lazily construct agents/providers, not initialized instances
  * @param httpClient
  *   shared client captured at registry creation; absent only for registries containing already-created agents alone
  * @param registeredAgents
  *   already-initialized agents supplied by the caller, such as HumanAgent or test agents; returned as-is, never
  *   constructed by this registry. Their IDs must not overlap with config.agents or duplicate another supplied ID
  * @param initializationLock
  *   registry-local semaphore protecting cache lookups, construction and insertion against concurrent initialization
  */
final class RuntimeInstances[-R] private (
    config: Config,
    httpClient: Option[Client],
    registeredAgents: Map[AgentId, Agent[R]],
    initializationLock: Semaphore
) {

  /** Environment-bound agents constructed lazily from config.agents; supplied agents retain R in registeredAgents. */
  private var initializedAgents = Map.empty[AgentId, (Agent[Any], Option[FsRead])]

  /** Only explicit credential replacement changes these definitions; other configuration remains a snapshot. */
  private var providerDefinitions = config.providers

  /** Preserve rooted capabilities when dependent agents are rebound after login; never re-resolve their roots. */
  private var initializedFilesystems = Map.empty[AgentId, FileSystem]

  /** Connections constructed lazily from provider definitions and shared by agents referencing the same provider ID. */
  private var initializedProviders = Map.empty[ProviderId, LlmProvider | SOMProvider]

  /** Trusted user identities retained for this registry's lifetime, independently of connection presence. */
  private var users = Set.empty[AgentId]

  /** All configured, supplied and user IDs; does not initialize agents or inspect their workspaces. */
  def listAgentIds: UIO[Chunk[AgentId]] =
    initializationLock.withPermit {
      ZIO.succeed(
        Chunk.fromIterable(
          (config.agents.keySet ++ registeredAgents.keySet ++ users).toList.sortBy(AgentId.value)
        )
      )
    }

  /** Registers a trusted user identity for authorship, not an executable agent or an authenticated account.
    *
    * Idempotent for users registered by this operation. Configured and caller-supplied IDs cannot be overwritten.
    * Registration survives connection cleanup but does not create a turn implementation.
    */
  def registerUser(id: AgentId): IO[YallmoError, Unit] =
    initializationLock.withPermit {
      ZIO.suspendSucceed {
        if config.agents.contains(id) || registeredAgents.contains(id) then
          ZIO.fail(
            YallmoError.InvalidConfig(s"User id '${AgentId.value(id)}' conflicts with an existing agent")
          )
        else ZIO.succeed { users = users + id }
      }
    }

  /** True only for identities registered by registerUser, not configured or caller-supplied agents. */
  def isUser(id: AgentId): UIO[Boolean] =
    initializationLock.withPermit(ZIO.succeed(users.contains(id)))

  /** Accepts registered users as authors without an agent; executable agents retain normal lookup validation. */
  def validateAuthor(id: AgentId): IO[YallmoError, Unit] =
    isUser(id).flatMap {
      case true  => ZIO.unit
      case false => getAgent(id).unit
    }

  /** Resolves one connection, without authenticating or contacting its endpoint.
    *
    * @param id
    *   configured provider instance/account ID, not its adapter type
    */
  def getProvider(id: ProviderId): IO[YallmoError, LlmProvider | SOMProvider] =
    initializationLock.withPermit(getProviderNow(id))

  /** Publishes a fresh ChatGPT connection and invalidates only its configured dependent agents. No network request,
    * config-file read/write, model-setting reload or filesystem reopening occurs. Existing in-flight bindings retain
    * their previous connection; all subsequent lookups share the replacement and its own serialized refresh state.
    * Invalid credentials, missing profiles and non-ChatGPT/untrusted definitions leave all runtime state unchanged.
    */
  def updateChatGptCredentials(id: ProviderId, credentials: OAuthCredentialsConfig): IO[YallmoError, Unit] =
    initializationLock.withPermit {
      for {
        definition <- ZIO.suspendSucceed {
          providerDefinitions.get(id) match {
            case Some(value: ProviderConfig.ChatGPT) => ZIO.succeed(value.copy(credentials = Some(credentials)))
            case _ => ZIO.fail(YallmoError.InvalidConfig("Live login requires an existing ChatGPT runtime profile"))
          }
        }
        replacement <- initializeProvider(definition)
        _ <- ZIO.succeed {
          providerDefinitions = providerDefinitions.updated(id, definition)
          initializedProviders = initializedProviders.updated(id, replacement)
          initializedAgents = initializedAgents.filterNot { case (agentId, _) =>
            config.agents.get(agentId).exists(_.providerIds.contains(id))
          }
        }
      } yield ()
    }

  /** Returns a supplied or cached agent; otherwise initializes the configured agent lazily.
    *
    * Registered users have no executable agent and fail with UnknownAgent.
    *
    * @param id
    *   ID of a supplied agent or definition in config.agents
    */
  def getAgent(id: AgentId): IO[YallmoError, Agent[R]] =
    getAgentWithFilesystem(id).map(_._1)

  /** Retains the exact configured capability; None means no private root, not a failed open. */
  def getAgentWithFilesystem(id: AgentId): IO[YallmoError, (Agent[R], Option[FsRead])] =
    initializationLock.withPermit {
      ZIO.suspendSucceed {
        registeredAgents.get(id).map(agent => (agent, None)).orElse(initializedAgents.get(id)) match {
          case Some(entry) => ZIO.succeed(entry)
          case None        =>
            for {
              definition <- ZIO.fromOption(config.agents.get(id)).orElseFail(YallmoError.UnknownAgent(id))
              fs <-
                if definition.filesystem.nonEmpty || Set("CoachAgent", "FolderStructureAgent").contains(
                    definition.agentType
                  )
                then openAgentFileSystem(id, definition).map(Some(_))
                else ZIO.none
              underlying <- initializeAgent(id, definition, fs)
              policy =
                if definition.agentType == "CoachAgent" then CoachAgent.Capabilities.intersect(definition.capabilities)
                else definition.capabilities
              agent = ConfiguredAgent(underlying, policy)
              entry = (agent, fs)
              _ <- ZIO.succeed { initializedAgents = initializedAgents.updated(id, entry) }
            } yield entry
        }
      }
    }

  /** Checks local adapter capability only; endpoint/model audio support is verified only at invocation time. */
  def getAudioAgent(id: AgentId): IO[YallmoError, AudioAgent[R]] =
    getAgent(id).flatMap {
      // getAgent already preserves R; only the erased audio-capability marker needs checking here.
      case audio: AudioAgent[?] => ZIO.succeed(audio.asInstanceOf[AudioAgent[R]])
      case _                    =>
        ZIO.fail(
          YallmoError.InvalidConfig(
            s"Agent '${AgentId.value(id)}' does not support audio turns (requires an audio-capable agent and provider)"
          )
        )
    }

  // Called with the initialization permit already held, including from agent initialization (no recursive locking).
  private def getProviderNow(id: ProviderId): IO[YallmoError, LlmProvider | SOMProvider] =
    ZIO.suspendSucceed {
      initializedProviders.get(id) match {
        case Some(provider) => ZIO.succeed(provider)
        case None           =>
          for {
            definition <- ZIO
              .fromOption(providerDefinitions.get(id))
              .orElseFail(
                YallmoError.InvalidConfig(s"No provider is configured with id '${ProviderId.value(id)}'")
              )
            provider <- initializeProvider(definition)
            _ <- ZIO.succeed { initializedProviders = initializedProviders.updated(id, provider) }
          } yield provider
      }
    }

  private def initializeProvider(definition: ProviderConfig): IO[YallmoError, LlmProvider] =
    for {
      client <- ZIO
        .fromOption(httpClient)
        .orElseFail(
          YallmoError.InvalidConfig("Configured providers require an HTTP client")
        )
      provider <- definition match {
        case ProviderConfig.OAI(endpoint) =>
          for {
            config <- ZIO.fromEither(OAIConfig.fromEndpoint(endpoint))
            outgoing <- ZIO.fromEither(fmgp.yallmo.provider.SandboxProviderHttpClient.configure(client, endpoint))
          } yield OAILlmProvider(outgoing, config)
        case ProviderConfig.ChatGPT(credentials, endpoint) =>
          for {
            _ <- ZIO
              .fail(YallmoError.InvalidConfig("ChatGPT endpoint must be the trusted subscription endpoint"))
              .unless(endpoint.forall(_.trim.stripSuffix("/") == OpenAiCodexClient.CodexResponsesUrl))
            _ <- ZIO
              .fail(YallmoError.InvalidConfig("Invalid ChatGPT profile credentials"))
              .unless(
                credentials.forall(value =>
                  List(value.accessToken, value.refreshToken)
                    .forall(token => token.nonEmpty && !token.exists(_.isWhitespace)) && value.expiresAtMillis >= 0
                )
              )
            connection <- OpenAiCodexClient
              .make(credentials)
              .provideEnvironment(ZEnvironment(client))
          } yield connection
      }
    } yield provider

  private def initializeAgent(
      id: AgentId,
      definition: AgentConfig,
      filesystem: Option[FileSystem]
  ): IO[YallmoError, Agent[Any]] =
    definition.agentType match {
      case "OAIAgent" => // TODO make it type safe
        for {
          provider <- singleProvider(definition, "OAI")
          agent <- provider match {
            case connection: OAILlmProvider => ZIO.succeed(OAIAgent.make(id, connection))
            case _                          => ZIO.fail(YallmoError.InvalidConfig("OAIAgent requires an OAI provider"))
          }
        } yield agent
      case "ChatGPTAgent" => // TODO make it type safe
        for {
          provider <- singleProvider(definition, "ChatGPT")
          agent <- provider match {
            case connection: OpenAiCodexClient => ZIO.succeed(new ChatGPTAgent(id, connection))
            case _ => ZIO.fail(YallmoError.InvalidConfig("ChatGPTAgent requires a ChatGPT provider"))
          }
        } yield agent
      case "CoachAgent" =>
        for {
          provider <- definition.providerIds.toList match {
            case providerId :: Nil => getProviderNow(providerId)
            case _                 => ZIO.fail(YallmoError.InvalidConfig("CoachAgent requires exactly one provider id"))
          }
          delegate <- provider match {
            case connection: OAILlmProvider    => ZIO.succeed(OAIAgent.make(id, connection))
            case connection: OpenAiCodexClient => ZIO.succeed(new ChatGPTAgent(id, connection))
            case _ => ZIO.fail(YallmoError.InvalidConfig("CoachAgent requires an OAI or ChatGPT provider"))
          }
          fs <- ZIO.fromOption(filesystem).orElseFail(YallmoError.InvalidConfig("CoachAgent requires filesystem.root"))
          agent <- CoachAgent.make(delegate, fs)
        } yield agent
      case "FolderStructureAgent" =>
        ZIO
          .fromOption(filesystem)
          .orElseFail(YallmoError.InvalidConfig("FolderStructureAgent requires filesystem.root"))
          .map(fs => FolderStructureAgent.make(id, fs))
      case name =>
        ZIO.fail(
          YallmoError.InvalidConfig(
            s"Unsupported agentType ($name); expected OAIAgent, ChatGPTAgent, CoachAgent or FolderStructureAgent"
          )
        )
    }

  private def openAgentFileSystem(id: AgentId, definition: AgentConfig): IO[YallmoError, FileSystem] =
    ZIO.suspendSucceed {
      initializedFilesystems.get(id) match {
        case Some(fs) => ZIO.succeed(fs)
        case None     =>
          openAgentFileSystemNow(id, definition).tap(fs =>
            ZIO.succeed { initializedFilesystems = initializedFilesystems.updated(id, fs) }
          )
      }
    }

  private def openAgentFileSystemNow(id: AgentId, definition: AgentConfig): IO[YallmoError, FileSystem] =
    for {
      settings <- ZIO
        .fromOption(definition.filesystem)
        .orElseFail(YallmoError.InvalidConfig(s"${definition.agentType} requires filesystem.root"))
      _ <- ZIO
        .fail(YallmoError.InvalidConfig(s"Agent '${AgentId.value(id)}' filesystem.root must not be blank"))
        .when(settings.root.trim.isEmpty)
      // Resolve trusted configuration once; workspace operations still reject symbolic links.
      root <- ZIO
        .attemptBlocking(Path.of(settings.root).toRealPath())
        .mapError(error =>
          YallmoError.InvalidConfig(
            s"Agent '${AgentId.value(id)}' filesystem: Cannot resolve filesystem.root: ${error.getMessage}"
          )
        )
      fs <- FileSystem
        .open(root.toString)
        .mapError(error => YallmoError.InvalidConfig(s"Agent '${AgentId.value(id)}' filesystem: $error"))
    } yield fs

  private def singleProvider(
      definition: AgentConfig,
      expectedType: String
  ): IO[YallmoError, LlmProvider | SOMProvider] =
    definition.providerIds.toList match {
      case id :: Nil =>
        providerDefinitions.get(id) match {
          case Some(_: ProviderConfig.OAI) if expectedType != "OAI" =>
            ZIO.fail(YallmoError.InvalidConfig("ChatGPTAgent requires a ChatGPT provider"))
          case Some(_: ProviderConfig.ChatGPT) if expectedType != "ChatGPT" =>
            ZIO.fail(YallmoError.InvalidConfig("OAIAgent requires an OAI provider"))
          case _ => getProviderNow(id)
        }
      case _ => ZIO.fail(YallmoError.InvalidConfig("Native agents require exactly one provider id"))
    }
}

object RuntimeInstances {

  /** Captures the shared HTTP client without eagerly initializing configured agents or providers.
    *
    * @param config
    *   immutable agent/provider definitions; this overload supplies no already-initialized agents
    */
  def make(config: Config): ZIO[Client, YallmoError, RuntimeInstances[Any]] =
    make(config, Chunk.empty[Agent[Any]])

  /** Adds already-created agents (e.g. a human input channel) alongside configured agents.
    *
    * Duplicate supplied IDs and collisions with configured IDs are rejected rather than silently overwritten.
    *
    * @tparam R
    *   per-turn environment required by the supplied agents
    * @param config
    *   immutable definitions for additional agents/providers to initialize lazily
    * @param agents
    *   already-initialized instances to register as-is, not configuration definitions; their IDs must be unique and
    *   absent from config.agents
    */
  def make[R](config: Config, agents: Chunk[Agent[R]]): ZIO[Client, YallmoError, RuntimeInstances[R]] =
    for {
      client <- ZIO.service[Client]
      instances <- create(config, Some(client), agents)
    } yield instances

  /** Builds a registry containing only already-created agents, without requiring an HTTP client.
    *
    * This supports existing application startup and offline/test agents. Its internal configuration catalogs are empty.
    *
    * @tparam R
    *   per-turn environment required by the supplied agents
    * @param agents
    *   already-initialized instances to register as-is; duplicate IDs are rejected
    */
  def fromAgents[R](agents: Chunk[Agent[R]]): IO[YallmoError, RuntimeInstances[R]] =
    create(Config(), None, agents)

  private def create[R](
      config: Config,
      client: Option[Client],
      agents: Chunk[Agent[R]]
  ): IO[YallmoError, RuntimeInstances[R]] =
    for {
      _ <- ZIO.fromEither(validateAgentIds(config, agents))
      lock <- Semaphore.make(1)
    } yield new RuntimeInstances(config, client, agents.map(agent => agent.id -> agent).toMap, lock)

  private def validateAgentIds[R](config: Config, agents: Chunk[Agent[R]]): Either[YallmoError, Unit] = {
    val ids = agents.map(_.id)
    for {
      _ <- Either.cond(
        ids.distinct.size == ids.size,
        (),
        YallmoError.InvalidConfig("Duplicate already-created agent ids")
      )
      _ <- Either.cond(
        !ids.exists(config.agents.contains),
        (),
        YallmoError.InvalidConfig("Already-created agent id conflicts with a configured agent")
      )
    } yield ()
  }
}
