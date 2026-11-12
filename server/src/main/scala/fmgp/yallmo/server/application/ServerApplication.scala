package fmgp.yallmo.server.application

import fmgp.yallmo.model.{Agent, AgentId, AudioAgent, Message, MessageId, YallmoError}
import fmgp.yallmo.tool.ToolDefinition

import fmgp.yallmo.provider.openai.OpenAiCodexAuth
import fmgp.yallmo.tool.sdlc.SdlcService
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{Config, ConfiguredAgentSettings, ProviderId}
import fmgp.yallmo.fs.FsRead
import fmgp.rpc.RpcWebSocket
import fmgp.yallmo.model.llm.ProviderContinuation
import fmgp.yallmo.orchestration.config.ConfigFile
import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import scala.collection.immutable.VectorMap
import zio.json.ast.Json
import java.util.concurrent.ConcurrentHashMap
import zio.*
import zio.http.Client

/** Shared application composition: one agent catalog, orchestration master and bound tool environment.
  *
  * This is the trusted in-process API for resolving agents and using core execution. The standalone factories
  * [[ServerApplication.open]] and [[ServerApplication.openRpc]] return [[ServerExecutionService]], which adds server
  * request policy and application-owned execution lifetime around this same composition. Neither construction nor agent
  * selection runs inference. Embedded factories do not acquire the standalone storage lease.
  */
final class ServerApplication private (
    val master: OrchestratorLive[Any],
    val catalog: AgentCatalog[Any],
    config: Config,
    availableContext: StaticContext[Any],
    val availability: ToolAvailability
) {
  import ServerApplication.*

  def registerUser(id: AgentId): IO[YallmoError, Unit] = catalog.registerUser(id)
  def isUser(id: AgentId): UIO[Boolean] = catalog.isUser(id)

  val browserAudioPort: Int = config.browserAudioPort

  /** Display projection only: no provider definitions, credentials, prompts or filesystem roots. */
  def agentSummaries: Vector[AgentSummary] =
    config.agents.toVector.sortBy(pair => AgentId.value(pair._1)).map { case (id, definition) =>
      AgentSummary(
        id,
        displayText(definition.agentType),
        definition.providerIds.map(value => displayText(ProviderId.value(value))).toVector,
        definition.config.fields.collectFirst {
          case ("model", Json.Str(value)) if value.trim.nonEmpty => displayText(value.trim)
        }
      )
    }

  /** Trusted in-process audio adapter only; this is not a wire value or remote execution API. */
  def resolveTranscriber[R](
      id: AgentId,
      available: StaticContext[R]
  ): IO[YallmoError, (AudioAgent[Any], ConfiguredAgentSettings)] = {
    val checked = for {
      definition <- ZIO
        .fromOption(config.agents.get(id))
        .orElseFail(YallmoError.InvalidConfig("Transcriber must reference a configured agent"))
      settings <- ZIO.fromEither(ConfiguredAgentSettings.decode(definition))
      context <- ZIO.fromEither(definition.capabilities.resolve(id, available))
      _ <- ZIO
        .fail(
          YallmoError.InvalidConfig(
            "Transcription requires effectively empty capabilities.tools/retrievers/skills/extensions; explicitly set each allowlist to [] to disable inherited capabilities"
          )
        )
        .unless(
          context.functions.isEmpty && context.retrievers.isEmpty && context.skills.isEmpty && context.extensions.isEmpty
        )
      agent <- catalog
        .getAudioAgent(id)
        .mapError(_ => YallmoError.InvalidConfig("Selected agent requires an audio-capable agent and provider"))
    } yield (agent, settings)
    checked.mapError(error =>
      YallmoError.InvalidConfig(s"Transcriber '${displayText(AgentId.value(id))}': ${displayText(error.getMessage)}")
    )
  }

  def context(selection: Option[ResolvedAgent]): StaticContext[Any] = selection.fold(availableContext)(_.context)

  def resolve(id: AgentId): IO[YallmoError, ResolvedAgent] =
    catalog
      .resolve(id)
      .tapError(error =>
        ZIO.logError(s"Unable to load agent '${displayText(AgentId.value(id))}': ${displayText(error.getMessage)}")
      )

  def invoke(input: Message, selection: ResolvedAgent): IO[YallmoError, Message] =
    master.invoke(input, AgentRunner.invocation(selection))

  def submitBranch(
      author: AgentId,
      parent: MessageId,
      prompt: String,
      selection: ResolvedAgent
  ): IO[BranchSubmitFailure, BranchTurn] =
    master.submitBranch(
      parent,
      author,
      AgentTask(prompt),
      AgentRunner.execution(selection, program => AgentContinuations.withCurrentContinuations(program, Int.MaxValue))
    )
}

object ServerApplication {
  val DefaultConfigPath: Path = Path.of("yallmo.json")

  /** IDs retain lookup identity; callers must sanitize IDs when rendering, as with catalog entries. Model reports only
    * a non-blank explicit setting; None does not imply a default, validation result or applicability.
    */
  final case class AgentSummary(id: AgentId, agentType: String, providerIds: Vector[String], model: Option[String])

  private def displayText(value: String): String = value.map { char =>
    if Character.isISOControl(char) || Set(
        Character.FORMAT.toInt,
        Character.LINE_SEPARATOR.toInt,
        Character.PARAGRAPH_SEPARATOR.toInt
      ).contains(Character.getType(char))
    then '?'
    else char
  }

  val DefaultSystemPrompt =
    "You are Yallmo, a concise terminal assistant. Answer the user's request concisely. Use an advertised tool only when it is needed."

  /** Trusted embedded construction. Opens one storage owner; selection stays lazy. No standalone lease is claimed. */
  def make(
      config: Config,
      registeredAgents: Chunk[Agent[Any]],
      storageRoot: Option[Path] = None,
      availableFunctions: Chunk[AgentFunction[Any]] = Chunk.empty,
      workspaceFilesystem: Option[FsRead] = None,
      selectedFunctions: Option[FsRead] => Chunk[AgentFunction[Any]] = _ => Chunk.empty,
      toolDefinitions: Chunk[ToolDefinition] = ApplicationTools.ToolDefinitions
  ): ZIO[Client, YallmoError, ServerApplication] =
    for {
      context = StaticContext[Any](
        DefaultSystemPrompt,
        functions = availableFunctions.map(_.definition),
        tools = ToolDefinition
          .available(toolDefinitions, availableFunctions.map(_.definition))
      )
      _ <- ZIO.foreachDiscard(config.agents.toList) { case (id, definition) =>
        val available = if definition.agentType == "CoachAgent" then
          context.copy(
            functions = context.functions ++ fmgp.yallmo.agents.CoachApplicationTools.Definitions,
            tools = context.tools :+ fmgp.yallmo.model.tool.CoachToolDefinition
          )
        else context
        ZIO.fromEither(definition.capabilities.resolve(id, available))
      }
      reader <- Promise.make[Nothing, OrchestratorLive[Any]]
      catalog <- AgentCatalog.make(
        config,
        registeredAgents,
        context,
        fs => {
          val rebound = selectedFunctions(fs.orElse(workspaceFilesystem))
          if rebound.isEmpty then ApplicationTools.selected(availableFunctions, fs.orElse(workspaceFilesystem), None)
          else rebound
        },
        id => reader.await.flatMap(_.readBlob(id))
      )
      master <- storageRoot match {
        case None       => OrchestratorLive.inMemory(catalog)
        case Some(root) => OrchestratorLive.filesystem(root, catalog)
      }
      _ <- reader.succeed(master)
    } yield new ServerApplication(
      master,
      catalog,
      config,
      context,
      ToolAvailability.from(availableFunctions.map(_.definition), Chunk.empty, toolDefinitions)
    )

  /** Configuration file boundary for retained embedded callers; the captured client must outlive the application. */
  def load(
      registeredAgents: Chunk[Agent[Any]],
      configPath: Path = DefaultConfigPath,
      storageRoot: Option[Path] = None
  ): ZIO[Client, YallmoError, (ServerApplication, ProviderCatalog)] =
    for {
      config <- ConfigFile.loadOrInitialize(configPath)
      application <- make(config, registeredAgents, storageRoot)
      providers <- ProviderCatalog.make(configPath)
    } yield (application, providers)

  /** Bind startup services and selection-specific read tools once per cached agent generation. */
  def workspace(
      config: Config,
      registeredAgents: Chunk[Agent[Any]],
      storageRoot: Option[Path],
      workspace: Path,
      services: ULayer[ApplicationTools.Environment] = ApplicationTools.live,
      rpc: Option[(RpcWebSocket.RpcRoute, String)] = None,
      sharedSdlc: Option[SdlcService] = None
  ): ZIO[Scope & Client, YallmoError, ServerApplication] =
    for {
      environment <- services.build
      startup <- ApplicationTools.evaluate(workspace, storageRoot, sharedSdlc).provideEnvironment(environment)
      tools = startup.functions.map { tool =>
        new AgentFunction[Any] {
          val definition = tool.definition
          def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
            tool.execute(call).provideEnvironment(environment)
        }
      }
      selected = (fs: Option[FsRead]) =>
        ApplicationTools.selected(
          tools,
          fs,
          rpc.map { case (route, endpoint) =>
            (environment.add[RpcWebSocket.RpcRoute](route), endpoint)
          }
        )
      application <- make(config, registeredAgents, storageRoot, tools, startup.filesystem, selected)
    } yield new ServerApplication(
      application.master,
      application.catalog,
      config,
      application.context(None),
      startup.availability
    )

  type ToolEnvironment = ApplicationTools.Environment
  type Environment = Client & ToolEnvironment

  private val ownedRoots = ConcurrentHashMap.newKeySet[Path]()

  /** Provide around the entire open/listen/serve lifetime, not just registry construction: it captures the client. */
  val live: ZLayer[Any, Throwable, Environment] =
    Client.default ++ ApplicationTools.live

  /** Opens the scoped standalone execution service with explicit filesystem-enabled Scala RPC wiring. The caller owns
    * the loopback listener, route lifetime and trusted endpoint; the returned service must not outlive this scope or
    * its HTTP/tool services.
    */
  def openRpc(
      storage: Path,
      configPath: Path,
      workspace: Option[Path],
      endpoint: String,
      sharedSdlc: Option[SdlcService] = None
  ): ZIO[Scope & Environment & fmgp.rpc.RpcWebSocket.RpcRoute, Throwable, ServerExecutionService] =
    ZIO.serviceWithZIO[fmgp.rpc.RpcWebSocket.RpcRoute] { route =>
      openConfigured(storage, configPath, workspace, Some(route -> endpoint), sharedSdlc)
    }

  /** Opens the standalone execution service and its shared application under a scoped storage lease. The returned
    * service owns admitted work beyond individual caller waits, but must not escape this scope or the lifetime of the
    * supplied HTTP/tool services. No agent is selected or executed at startup.
    */
  def open(
      storage: Path,
      configPath: Path,
      workspace: Option[Path] = None,
      sharedSdlc: Option[SdlcService] = None
  ): ZIO[Scope & Environment, Throwable, ServerExecutionService] =
    openConfigured(storage, configPath, workspace, None, sharedSdlc)

  private def openConfigured(
      storage: Path,
      configPath: Path,
      workspace: Option[Path],
      rpc: Option[(fmgp.rpc.RpcWebSocket.RpcRoute, String)],
      sharedSdlc: Option[SdlcService]
  ): ZIO[Scope & Environment, Throwable, ServerExecutionService] =
    ZIO.uninterruptibleMask { restore =>
      for {
        parent <- ZIO.service[Scope]
        // Failed opens release ownership immediately, even if the caller catches the failure in a longer-lived scope.
        ownership <- parent.fork
        application <- restore(compose(storage, configPath, workspace, rpc, sharedSdlc))
          .provideSomeEnvironment[Scope & Environment](_.add[Scope](ownership))
          .onExit {
            case Exit.Success(_) => ZIO.unit
            case failure         => ownership.close(failure)
          }
      } yield application
    }

  private def compose(
      storage: Path,
      configPath: Path,
      workspace: Option[Path],
      rpc: Option[(fmgp.rpc.RpcWebSocket.RpcRoute, String)],
      sharedSdlc: Option[SdlcService]
  ): ZIO[Scope & Environment, Throwable, ServerExecutionService] =
    for {
      root <- existingDirectory(storage, "Storage directory must already exist and be accessible")
      _ <- lease(root)
      selectedWorkspace <- existingDirectory(
        workspace.getOrElse(Path.of("")),
        "Workspace directory must already exist and be accessible"
      )
      config <- ConfigFile.loadOrInitialize(configPath)
      environment <- ZIO.environment[ToolEnvironment]
      opened <- ServerApplication
        .workspace(
          config,
          Chunk.empty,
          Some(root),
          selectedWorkspace,
          ZLayer.succeedEnvironment(environment),
          rpc,
          sharedSdlc
        )
        .mapError(error =>
          new IllegalStateException(
            s"Unable to initialize configured application: ${error.getMessage}",
            error
          )
        )
      // Registered after storage/tools: all application work finalizes before their ownership is released.
      scope <- ZIO.service[Scope].flatMap(_.fork)
      inFlight <- Ref.make(Set.empty[SessionId])
      continuations <- Ref.make(Map.empty[SessionId, (AgentId, Set[ProviderContinuation])])
      captures <- Ref.make(VectorMap.empty[SessionId, Path])
      catalog <- ProviderCatalog.make(configPath)
      client <- ZIO.service[Client]
      providers <- ProviderAdministration.make(
        catalog,
        OpenAiCodexAuth.oauthFlow(client),
        (id, credentials) => opened.catalog.updateChatGptCredentials(id, credentials).fold(_ => false, _ => true)
      )
    } yield new ServerExecutionService(opened, selectedWorkspace, providers, scope, inFlight, continuations, captures)

  private def existingDirectory(path: Path, message: String): Task[Path] =
    ZIO
      .attemptBlocking {
        val canonical = path.toRealPath()
        if !Files.isDirectory(canonical) then throw new IllegalArgumentException(message)
        canonical
      }
      .mapError(_ => new IllegalArgumentException(message))

  /** Cooperative standalone ownership only, not a storage mutation mutex or an external-writer/auth boundary. Never
    * unlink the persistent lock file: another owner could otherwise lock a different inode at the same path.
    */
  private[server] def lease(root: Path): ZIO[Scope, Throwable, Unit] =
    for {
      // On some systems closing a second channel can release this JVM's existing OS lock. Reject before opening it.
      _ <- ZIO.acquireRelease(
        ZIO.attempt {
          if !ownedRoots.add(root) then
            throw new IllegalStateException("Storage directory is already owned by a standalone server")
        }
      )(_ => ZIO.succeed { ownedRoots.remove(root); () })
      channel <- ZIO.acquireRelease(
        ZIO
          .attemptBlocking {
            FileChannel.open(
              root.resolve(".yallmo-server.lock"),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS
            )
          }
          .mapError(_ =>
            new IllegalStateException("Unable to acquire server storage lease; lock file must not be a symlink")
          )
      )(channel => ZIO.attemptBlocking(channel.close()).orDie)
      _ <- ZIO.acquireRelease(
        ZIO
          .attemptBlocking {
            val lock = try channel.tryLock()
            catch { case _: OverlappingFileLockException => null }
            if lock == null then
              throw new IllegalStateException("Storage directory is already owned by a standalone server")
            lock
          }
          .mapError(_ =>
            new IllegalStateException("Storage directory is already owned or its server lease is unavailable")
          )
      )(lock => ZIO.attemptBlocking(lock.release()).orDie)
    } yield ()
}
