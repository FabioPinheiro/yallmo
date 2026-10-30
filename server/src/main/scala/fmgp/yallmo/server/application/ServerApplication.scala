package fmgp.yallmo.server.application

import fmgp.yallmo.LlmProviderPayloadDebug
import fmgp.yallmo.model.{Agent, AgentId, AudioAgent, Message, MessageId, MessagePayload, YallmoError}
import fmgp.yallmo.orchestration.{OrchestratorLive, RuntimeInstances, SessionId}
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import fmgp.yallmo.orchestration.config.ConfigFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.channels.{FileChannel, OverlappingFileLockException}
import java.nio.file.{Files, LinkOption, NoSuchFileException, Path, StandardOpenOption}
import scala.collection.immutable.VectorMap
import zio.json.*
import zio.json.ast.Json
import java.util.concurrent.ConcurrentHashMap
import zio.*
import zio.http.Client

/** One standalone storage owner. Configured composition is retained without selecting or executing an agent. */
final class ServerApplication private (
    val master: OrchestratorLive[Any],
    val configured: Option[ServerApplication.Configured]
)

object ServerApplication {
  type ToolEnvironment = ApplicationTools.Environment
  type Environment = Client & ToolEnvironment

  private val ownedRoots = ConcurrentHashMap.newKeySet[Path]()
  private val MaxCapturedSessions = 128

  /** Process-lifetime trusted user registration is separate from ephemeral connection presence. The scope owns
    * execution; callers own only interruptible waits. No results or run IDs are retained here.
    */
  final class Configured private[application] (
      val runtime: ApplicationRuntime,
      val assembly: ApplicationRuntime.Assembly[Any],
      val workspace: Path,
      scope: Scope,
      inFlight: Ref[Set[SessionId]],
      continuations: Ref[Map[SessionId, (AgentId, Int)]],
      requestCaptures: Ref[VectorMap[SessionId, Path]]
  ) {
    def create(author: AgentId, name: Option[SessionId]): IO[JsonRpc.Error, SessionSnapshot] =
      owned {
        for {
          _ <- checked(runtime.registerUser(author))
          session <- checked(assembly.submission(author, None).create(name.map(_.value)))
        } yield SessionSnapshot.from(session)
      }()

    def submit(
        author: AgentId,
        sessionId: SessionId,
        agentId: AgentId,
        prompt: String,
        expectedHead: Option[MessageId] = None,
        resetContinuation: Boolean = false
    ): IO[JsonRpc.Error, TurnOutcome] =
      owned {
        ZIO.uninterruptibleMask { restore =>
          for {
            admitted <- inFlight.modify(ids => (!ids.contains(sessionId), ids + sessionId))
            _ <- ZIO.fail(JsonRpc.Error(ErrorCode.Busy, "Session is busy")).unless(admitted)
            outcome <- restore((for {
              binding <- checked(runtime.resolve(agentId))
              previous <- checked(assembly.orchestrator.loadSession(sessionId))
              _ <- ZIO
                .fail(JsonRpc.Error(ErrorCode.Conflict, "Session head changed"))
                .when(expectedHead.exists(head => !previous.messages.lastOption.exists(_.id == head)))
              _ <- checked(runtime.registerUser(author))
              policies <- continuations.get
              boundary = policies.get(sessionId) match {
                case Some((id, start)) if id == agentId && !resetContinuation => start
                case _                                                        => previous.messages.size
              }
              _ <- continuations.update(_.updated(sessionId, (agentId, boundary)))
              outcome <- captureRequest(sessionId)(
                assembly
                  .submission(author, Some(binding))
                  .submit(Some(sessionId.value), prompt, boundary)
                  .fold(
                    failure =>
                      TurnOutcome.Failed(
                        if failure.acceptedSession.nonEmpty then PromptAcceptance.Accepted
                        else PromptAcceptance.Unknown,
                        failure.acceptedSession.map(SessionSnapshot.from),
                        ApplicationError("execution_failed", "Execution failed; refresh session before continuing")
                      ),
                    turn =>
                      TurnOutcome.Succeeded(
                        SessionSnapshot.from(turn.session),
                        turn.result,
                        turn.session.messages.drop(previous.messages.size).map(MessageSnapshot.from)
                      )
                  )
              )
            } yield outcome)).ensuring(inFlight.update(_ - sessionId))
          } yield outcome
        }
      } { cause =>
        cause.failureOption match {
          case Some(error) if cause.defects.isEmpty && !cause.isInterrupted => ZIO.fail(error)
          case _                                                            =>
            assembly.orchestrator.loadSession(sessionId).exit.map { snapshot =>
              TurnOutcome.Failed(
                PromptAcceptance.Unknown,
                snapshot.toEither.toOption.map(SessionSnapshot.from),
                ApplicationError("execution_unknown", "Execution outcome unknown; refresh session before continuing")
              )
            }
        }
      }

    /** Independent configured execution; the application owns work and the caller owns only its wait. No session,
      * continuation policy or session diagnostic capture is created. Accepted input survives execution failure.
      */
    def invoke(author: AgentId, agentId: AgentId, input: Message): IO[JsonRpc.Error, Message] =
      owned {
        for {
          _ <- ZIO
            .fail(JsonRpc.Error(ErrorCode.Forbidden, "Message author must match the initialized identity"))
            .unless(input.agentId == author)
          binding <- checked(runtime.resolve(agentId))
          _ <- ZIO
            .fail(JsonRpc.Error(ErrorCode.Rejected, "Selected agent does not support audio input"))
            .when(
              input.payload.isInstanceOf[MessagePayload.AudioAttachment] &&
                !binding.adapter.isInstanceOf[AudioAgent[?]]
            )
          _ <- checked(runtime.registerUser(author))
          result <- checked(assembly.invoke(input, binding))
        } yield result
      }()

    /** Explicit read-only diagnostic; session lookup prevents unknown IDs from becoming a path lookup. */
    def providerRequest(sessionId: SessionId): IO[JsonRpc.Error, DiagnosticsRequest.Result] =
      for {
        _ <- checked(assembly.orchestrator.loadSession(sessionId))
        captures <- requestCaptures.get
        document <- ZIO.foreach(captures.get(sessionId))(readCapture)
      } yield DiagnosticsRequest.Result(document.flatten)

    /** Runs inside application-owned work, so capture association survives loss of the caller's connection/wait. Clear
      * before a new execution: a turn without a capture must never present a stale earlier request as its own.
      */
    private def captureRequest[A](sessionId: SessionId)(effect: UIO[A]): UIO[A] =
      ZIO.uninterruptibleMask { restore =>
        for {
          _ <- requestCaptures.update(_ - sessionId)
          captured <- LlmProviderPayloadDebug.withRequestCapture(restore(effect).exit)
          (outcome, path) = captured
          _ <- ZIO.foreachDiscard(path)(value =>
            requestCaptures.update { previous =>
              val updated = previous.updated(sessionId, value.toAbsolutePath.normalize)
              if updated.size > MaxCapturedSessions then updated.tail else updated
            }
          )
          value <- ZIO.suspendSucceed(outcome)
        } yield value
      }

    private def readCapture(path: Path): IO[JsonRpc.Error, Option[Json.Obj]] =
      ZIO
        .attemptBlocking[Either[JsonRpc.Error, Option[Json.Obj]]] {
          val stream = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
          val bytes = try stream.readNBytes(DiagnosticsRequest.MaxCaptureBytes + 1)
          finally stream.close()
          if bytes.length > DiagnosticsRequest.MaxCaptureBytes then
            Left(JsonRpc.Error(ErrorCode.Rejected, "Provider request capture exceeds the diagnostic limit"))
          else {
            val text = UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString
            text
              .fromJson[Json.Obj]
              .left
              .map(_ => JsonRpc.Error(ErrorCode.Rejected, "Provider request capture is unavailable"))
              .flatMap { document =>
                if document.toJson.getBytes(UTF_8).length > DiagnosticsRequest.MaxCaptureBytes then
                  Left(JsonRpc.Error(ErrorCode.Rejected, "Provider request capture exceeds the diagnostic limit"))
                else Right(Some(document))
              }
          }
        }
        .catchSome { case _: NoSuchFileException => ZIO.succeed(Right(None)) }
        .mapError(_ => JsonRpc.Error(ErrorCode.Rejected, "Provider request capture is unavailable"))
        .flatMap(ZIO.fromEither(_))

    private def owned[A](effect: IO[JsonRpc.Error, A])(
        onFailure: Cause[JsonRpc.Error] => IO[JsonRpc.Error, A] = cause =>
          ZIO.fail(cause.failureOption.getOrElse(JsonRpc.Error.InternalError))
    ): IO[JsonRpc.Error, A] =
      ZIO.uninterruptibleMask { restore =>
        for {
          terminal <- Promise.make[JsonRpc.Error, A]
          // Terminal mapping/delivery stays masked after interruptible work and its finalizers have stopped.
          fiber <- ZIO
            .uninterruptibleMask { _ =>
              effect.interruptible.exit.flatMap {
                case Exit.Success(value) => terminal.succeed(value).unit
                case Exit.Failure(cause) =>
                  onFailure(cause).exit.flatMap {
                    case Exit.Success(value) => terminal.succeed(value).unit
                    case Exit.Failure(error) =>
                      terminal.fail(error.failureOption.getOrElse(JsonRpc.Error.InternalError)).unit
                  }
              }
            }
            .forkIn(scope)
          // A closed scope may interrupt forkIn before its body starts; no wait may be left hanging.
          _ <- fiber.poll.flatMap {
            case Some(_) => terminal.fail(JsonRpc.Error.InternalError).unit
            case None    => ZIO.unit
          }
          result <- restore(terminal.await)
        } yield result
      }

    private def checked[A](effect: IO[YallmoError, A]): IO[JsonRpc.Error, A] =
      effect.mapError {
        case YallmoError.StorageFailed(error) => checkedError(error)
        case error                            => checkedError(error)
      }

    private def checkedError(error: YallmoError): JsonRpc.Error = error match {
      case _: YallmoError.SessionNotFound | _: YallmoError.BlobMissing =>
        JsonRpc.Error(ErrorCode.NotFound, "Not found")
      case _: YallmoError.SessionAlreadyExists =>
        JsonRpc.Error(ErrorCode.Conflict, "Session already exists")
      case _: YallmoError.InvalidMessage =>
        JsonRpc.Error(ErrorCode.Rejected, "Invalid message for this invocation")
      case YallmoError.BlobUploadFailed(reason) =>
        JsonRpc.Error(ErrorCode.Rejected, reason.message)
      case _: YallmoError.UnknownAgent | _: YallmoError.InvalidConfig =>
        JsonRpc.Error(ErrorCode.Rejected, "Invalid author or assistant configuration")
      case _ => JsonRpc.Error.InternalError
    }
  }

  /** Provide around the entire open/listen/serve lifetime, not just registry construction: it captures the client. */
  val live: ZLayer[Any, Throwable, Environment] =
    Client.default ++ ApplicationTools.live

  /** The caller owns the loopback listener, route lifetime and trusted endpoint. */
  def openRpc(
      storage: Path,
      configPath: Option[Path],
      workspace: Option[Path],
      endpoint: String
  ): ZIO[Scope & Environment & fmgp.rpc.RpcWebSocket.RpcRoute, Throwable, ServerApplication] =
    ZIO.serviceWithZIO[fmgp.rpc.RpcWebSocket.RpcRoute] { route =>
      openConfigured(storage, configPath, workspace, Some(route -> endpoint))
    }

  /** Viewer mode never loads configuration or probes tools. All standalone modes acquire the same scoped lease. */
  def open(
      storage: Path,
      configPath: Option[Path] = None,
      workspace: Option[Path] = None
  ): ZIO[Scope & Environment, Throwable, ServerApplication] =
    openConfigured(storage, configPath, workspace, None)

  private def openConfigured(
      storage: Path,
      configPath: Option[Path],
      workspace: Option[Path],
      rpc: Option[(fmgp.rpc.RpcWebSocket.RpcRoute, String)]
  ): ZIO[Scope & Environment, Throwable, ServerApplication] =
    ZIO.uninterruptibleMask { restore =>
      for {
        parent <- ZIO.service[Scope]
        // Failed opens release ownership immediately, even if the caller catches the failure in a longer-lived scope.
        ownership <- parent.fork
        application <- restore(compose(storage, configPath, workspace, rpc))
          .provideSomeEnvironment[Scope & Environment](_.add[Scope](ownership))
          .onExit {
            case Exit.Success(_) => ZIO.unit
            case failure         => ownership.close(failure)
          }
      } yield application
    }

  private def compose(
      storage: Path,
      configPath: Option[Path],
      workspace: Option[Path],
      rpc: Option[(fmgp.rpc.RpcWebSocket.RpcRoute, String)]
  ): ZIO[Scope & Environment, Throwable, ServerApplication] =
    for {
      _ <- ZIO
        .fail(new IllegalArgumentException("--workspace requires --config"))
        .when(
          workspace.nonEmpty && configPath.isEmpty
        )
      root <- existingDirectory(storage, "Storage directory must already exist and be accessible")
      _ <- lease(root)
      application <- configPath match {
        case None =>
          for {
            registry <- RuntimeInstances.fromAgents(Chunk.empty[Agent[Any]])
            master <- OrchestratorLive
              .filesystem(root, registry)
              .mapError(error =>
                new IllegalStateException("Unable to open server storage; see cause for details", error)
              )
          } yield new ServerApplication(master, None)
        case Some(path) =>
          for {
            selectedWorkspace <- existingDirectory(
              workspace.getOrElse(Path.of("")),
              "Workspace directory must already exist and be accessible"
            )
            config <- ConfigFile.loadOrInitialize(path)
            runtime <- ApplicationRuntime.make(config, Chunk.empty)
            environment <- ZIO.environment[ToolEnvironment]
            opened <- (rpc match {
              case None => runtime.openWorkspace(Some(root), selectedWorkspace, ZLayer.succeedEnvironment(environment))
              case Some((route, endpoint)) =>
                runtime
                  .openWorkspaceRpc(Some(root), selectedWorkspace, ZLayer.succeedEnvironment(environment), endpoint)
                  .provideSomeEnvironment[Scope](_.add[fmgp.rpc.RpcWebSocket.RpcRoute](route))
            }).mapError(error =>
              new IllegalStateException(
                "Unable to initialize configured application; see cause for storage or capability validation details",
                error
              )
            )
            (assembly, _) = opened
            // Registered after storage/tools: all application work finalizes before their ownership is released.
            scope <- ZIO.service[Scope].flatMap(_.fork)
            inFlight <- Ref.make(Set.empty[SessionId])
            continuations <- Ref.make(Map.empty[SessionId, (AgentId, Int)])
            captures <- Ref.make(VectorMap.empty[SessionId, Path])
          } yield new ServerApplication(
            assembly.orchestrator,
            Some(new Configured(runtime, assembly, selectedWorkspace, scope, inFlight, continuations, captures))
          )
      }
    } yield application

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
  private def lease(root: Path): ZIO[Scope, Throwable, Unit] =
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
