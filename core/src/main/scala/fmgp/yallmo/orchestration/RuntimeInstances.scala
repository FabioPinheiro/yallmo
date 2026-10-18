package fmgp.yallmo.orchestration

import fmgp.yallmo.model.{Agent, AgentId, YallmoError}
import fmgp.yallmo.model.llm.LlmProvider
import fmgp.yallmo.model.som.SOMProvider
import fmgp.yallmo.orchestration.config.{AgentConfig, Config, ProviderConfig, ProviderId}
import fmgp.yallmo.provider.oai.{OAIAgent, OAIConfig, OAILlmProvider}
import fmgp.yallmo.provider.openai.{ChatGPTAgent, OpenAiCodexClient}
import zio.*
import zio.http.Client

/** Owns lazy runtime instances for one immutable configuration snapshot.
  *
  * Successful lookups are cached and concurrent initialization is serialized. Already-created agents retain their
  * identity and environment requirements; configured native agents need no per-turn environment. Neither construction
  * nor lookup makes network requests or reads environment variables. Failures are not cached.
  *
  * The captured HTTP client must outlive this registry. Reusing a Config with a new registry creates independent
  * caches; changing configuration does not mutate existing instances. ChatGPT refresh stays in connection-local memory.
  *
  * @tparam R
  *   per-turn environment required by caller-supplied agents; configured native agents require no extra services
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

  /** Agents constructed lazily from config.agents; caller-supplied instances remain in registeredAgents. */
  private var initializedAgents = Map.empty[AgentId, Agent[Any]]

  /** Connections constructed lazily from config.providers and shared by agents referencing the same provider ID. */
  private var initializedProviders = Map.empty[ProviderId, LlmProvider | SOMProvider]

  /** Resolves one connection, without authenticating or contacting its endpoint.
    *
    * @param id
    *   configured provider instance/account ID, not its adapter type
    */
  def getProvider(id: ProviderId): IO[YallmoError, LlmProvider | SOMProvider] =
    initializationLock.withPermit(getProviderNow(id))

  /** Returns a caller-supplied or cached agent; otherwise initializes the configured agent and its provider lazily.
    *
    * @param id
    *   ID of either an already-initialized caller-supplied agent or a definition in config.agents
    */
  def getAgent(id: AgentId): IO[YallmoError, Agent[R]] =
    initializationLock.withPermit {
      ZIO.suspendSucceed {
        registeredAgents.get(id).orElse(initializedAgents.get(id)) match {
          case Some(agent) => ZIO.succeed(agent)
          case None        =>
            for {
              definition <- ZIO.fromOption(config.agents.get(id)).orElseFail(YallmoError.UnknownAgent(id))
              agent <- initializeAgent(id, definition)
              _ <- ZIO.succeed { initializedAgents = initializedAgents.updated(id, agent) }
            } yield agent
        }
      }
    }

  // Called with the initialization permit already held, including from agent initialization (no recursive locking).
  private def getProviderNow(id: ProviderId): IO[YallmoError, LlmProvider | SOMProvider] =
    ZIO.suspendSucceed {
      initializedProviders.get(id) match {
        case Some(provider) => ZIO.succeed(provider)
        case None           =>
          for {
            definition <- ZIO
              .fromOption(config.providers.get(id))
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
          ZIO.fromEither(OAIConfig.fromEndpoint(endpoint)).map(config => OAILlmProvider(client, config))
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

  private def initializeAgent(id: AgentId, definition: AgentConfig): IO[YallmoError, Agent[Any]] =
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
      case name =>
        ZIO.fail(YallmoError.InvalidConfig(s"Unsupported agentType ($name); expected OAIAgent or ChatGPTAgent"))
    }

  private def singleProvider(
      definition: AgentConfig,
      expectedType: String
  ): IO[YallmoError, LlmProvider | SOMProvider] =
    definition.providerIds.toList match {
      case id :: Nil =>
        config.providers.get(id) match {
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

  /** Adds already-created agents (e.g. a human input channel) alongside configured native agents.
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
    create(Config(userName = ""), None, agents)

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
