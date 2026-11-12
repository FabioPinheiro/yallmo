package fmgp.yallmo.server.application

import fmgp.yallmo.LlmProviderPayloadDebug
import fmgp.yallmo.model.{AgentId, AudioAgent, Message, MessageId, MessagePayload, YallmoError}
import fmgp.yallmo.model.llm.ProviderContinuation
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, NoSuchFileException, Path, StandardOpenOption}
import scala.collection.immutable.VectorMap
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Server-facing execution service over one shared [[ServerApplication]], not a configuration value or another agent
  * runtime.
  *
  * Created by [[ServerApplication.open]] or [[ServerApplication.openRpc]] for the standalone server. RPC handlers use
  * this service rather than assembling runners or submitting directly to core orchestration. It adds:
  *   - Request policy: author registration, same-session Busy rejection, optional expected-head checks, and
  *     provider-continuation reset/agent-switch policy for session submissions.
  *   - Execution ownership: submissions, branch submissions and invocations run in the application's child scope.
  *     Disconnect or caller interruption abandons only the response wait; shutdown stops and finalizes the work before
  *     startup resources and the storage lease are released.
  *   - Server integration: sanitized JSON-RPC errors/outcomes, bounded session request-capture lookup, and access to
  *     provider administration.
  *
  * Agent construction/caching remains in the application's catalog, the decision/tool loop in AgentRunner, and
  * prompt/transcript persistence in OrchestratorLive. The in-flight set is session-submission admission, not a global
  * execution lock. Human registration is trusted authorship metadata, not authentication. This service retains no
  * run-status/result registry and provides no cancellation, replay or exactly-once guarantee.
  *
  * @param application
  *   shared catalog, tools and orchestration storage used by every operation
  * @param workspace
  *   canonical startup workspace; does not replace configured agent filesystem roots
  * @param providers
  *   server-owned provider catalog/login operations, including credential persistence and live activation
  * @param scope
  *   child execution scope that outlives individual connection waits but not the application
  * @param inFlight
  *   session IDs with an admitted submission; overlapping submissions fail Busy rather than queue
  * @param continuations
  *   per-session selected agent and opaque continuation identities excluded from provider requests, not stored history
  * @param requestCaptures
  *   bounded in-memory index of latest captured request paths per session; not a durable execution journal
  */
final class ServerExecutionService private[application] (
    val application: ServerApplication,
    val workspace: Path,
    val providers: ProviderAdministration,
    scope: Scope,
    inFlight: Ref[Set[SessionId]],
    continuations: Ref[Map[SessionId, (AgentId, Set[ProviderContinuation])]],
    requestCaptures: Ref[VectorMap[SessionId, Path]]
) {
  private val MaxCapturedSessions = 128

  /** Admits a session turn, resolves its agent, checks request policy and delegates prompt acceptance to orchestration.
    *
    * The application owns execution even if the caller stops waiting. Accepted input is not rolled back on later
    * failure; uncertain outcomes require inspecting saved history, not automatically resubmitting the prompt. Branch
    * submissions and single-message invocations use their own entry points and do not share this session gate.
    */
  def submit(
      author: AgentId,
      target: TurnSubmit.Target,
      agentId: AgentId,
      prompt: String,
      expectedHead: Option[MessageId] = None,
      resetContinuation: Boolean = false
  ): IO[JsonRpc.Error, TurnOutcome] = ZIO.suspendSucceed {
    val sessionId = target match {
      case TurnSubmit.Target.Existing(id) => id
      case TurnSubmit.Target.New(name)    => name.getOrElse(SessionId.unsafe(s"main-${java.util.UUID.randomUUID()}"))
    }
    owned {
      ZIO.uninterruptibleMask { restore =>
        for {
          admitted <- inFlight.modify(ids => (!ids.contains(sessionId), ids + sessionId))
          _ <- ZIO.fail(JsonRpc.Error(ErrorCode.Busy, "Session is busy")).unless(admitted)
          outcome <- restore((for {
            binding <- checked(application.resolve(agentId))
            _ <- ZIO
              .fail(JsonRpc.Error.InvalidParams)
              .when(target.isInstanceOf[TurnSubmit.Target.New] && expectedHead.nonEmpty)
            _ <- checked(application.registerUser(author))
            previous <- target match {
              case _: TurnSubmit.Target.Existing => checked(application.master.loadSession(sessionId))
              case _: TurnSubmit.Target.New      =>
                checked(application.master.createSession(sessionId, Chunk(author, agentId)))
            }
            _ <- ZIO
              .fail(JsonRpc.Error(ErrorCode.Conflict, "Session head changed"))
              .when(expectedHead.exists(head => !previous.messages.lastOption.exists(_.id == head)))
            policies <- continuations.get
            excluded = policies.get(sessionId) match {
              case Some((id, values)) if id == agentId && !resetContinuation => values
              case _ => AgentContinuations.continuations(previous.messages.map(_.payload))
            }
            _ <- continuations.update(_.updated(sessionId, (agentId, excluded)))
            outcome <- captureRequest(sessionId)(
              application.master
                .submit(
                  sessionId,
                  author,
                  AgentTask(prompt),
                  AgentRunner.execution(
                    binding,
                    program => AgentContinuations.withCurrentContinuations(program, 0, Some(excluded))
                  )
                )
                .tapError(failure => ZIO.logErrorCause("Session execution failed", Cause.fail(failure.error)))
                .fold(
                  failure =>
                    TurnOutcome.Failed(
                      if failure.acceptedSession.nonEmpty then PromptAcceptance.Accepted
                      else PromptAcceptance.Unknown,
                      Some(SessionSnapshot.from(failure.acceptedSession.getOrElse(previous))),
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
          application.master.loadSession(sessionId).exit.map { snapshot =>
            TurnOutcome.Failed(
              PromptAcceptance.Unknown,
              snapshot.toEither.toOption.map(SessionSnapshot.from),
              ApplicationError("execution_unknown", "Execution outcome unknown; refresh session before continuing")
            )
          }
      }
    }

  }

  /** A configured, application-owned managed loop with immutable ancestry and no session publication at all. */
  def submitBranch(
      author: AgentId,
      parent: MessageId,
      agentId: AgentId,
      prompt: String
  ): IO[JsonRpc.Error, BranchOutcome] =
    owned {
      for {
        binding <- checked(application.resolve(agentId))
        _ <- ZIO.fail(JsonRpc.Error.InvalidParams).when(prompt.trim.isEmpty)
        _ <- checked(application.registerUser(author))
        result <- application
          .submitBranch(author, parent, prompt, binding)
          .tapError(failure => ZIO.logErrorCause("Branch execution failed", Cause.fail(failure.error)))
          .foldZIO(
            failure =>
              if failure.acceptance == BranchAcceptance.NotAccepted then
                failure.error match {
                  case _: YallmoError.InvalidMessage =>
                    ZIO.fail(JsonRpc.Error(ErrorCode.Rejected, "Invalid or incomplete branch ancestry"))
                  case error => checked(ZIO.fail(error))
                }
              else
                ZIO.succeed(
                  BranchOutcome.Failed(
                    parent,
                    if failure.acceptance == BranchAcceptance.Accepted then PromptAcceptance.Accepted
                    else PromptAcceptance.Unknown,
                    failure.promptId,
                    failure.head,
                    failure.newMessages.map(MessageSnapshot.from),
                    ApplicationError(
                      "execution_failed",
                      "Branch execution failed; inspect saved messages before continuing"
                    )
                  )
                )
            ,
            turn =>
              ZIO.succeed(
                BranchOutcome.Succeeded(
                  parent,
                  turn.promptId,
                  turn.head,
                  turn.result,
                  turn.newMessages.map(MessageSnapshot.from)
                )
              )
          )
      } yield result
    } { cause =>
      cause.failureOption match {
        case Some(error) if cause.defects.isEmpty && !cause.isInterrupted => ZIO.fail(error)
        case _                                                            =>
          ZIO.succeed(
            BranchOutcome.Failed(
              parent,
              PromptAcceptance.Unknown,
              None,
              None,
              Chunk.empty,
              ApplicationError(
                "execution_unknown",
                "Branch outcome unknown; refresh and inspect messages; do not automatically retry"
              )
            )
          )
      }
    }

  /** Single-message configured invocation, never a model/tool loop. The application owns work and the caller owns only
    * its wait. No session, continuation policy or session diagnostic capture is created. Accepted input survives
    * execution failure. Model-requested actions require submit instead.
    */
  def invoke(author: AgentId, agentId: AgentId, input: Message): IO[JsonRpc.Error, Message] =
    owned {
      for {
        _ <- ZIO
          .fail(JsonRpc.Error(ErrorCode.Forbidden, "Message author must match the acting agent"))
          .unless(input.agentId == author)
        binding <- checked(application.resolve(agentId))
        _ <- ZIO
          .fail(JsonRpc.Error(ErrorCode.Rejected, "Selected agent does not support audio input"))
          .when(
            input.payload.isInstanceOf[MessagePayload.AudioAttachment] &&
              !binding.adapter.isInstanceOf[AudioAgent[?]]
          )
        _ <- checked(application.registerUser(author))
        result <- checked(application.invoke(input, binding))
      } yield result
    }()

  /** Explicit read-only diagnostic; session lookup prevents unknown IDs from becoming a path lookup. */
  def providerRequest(sessionId: SessionId): IO[JsonRpc.Error, DiagnosticsRequest.Result] =
    for {
      _ <- checked(application.master.loadSession(sessionId))
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
                (ZIO
                  .logErrorCause("Configured operation failed unexpectedly", cause)
                  .when(cause.defects.nonEmpty) *>
                  ZIO
                    .logWarning("Configured operation interrupted before terminal delivery")
                    .when(cause.isInterrupted) *>
                  onFailure(cause)).exit.flatMap {
                  case Exit.Success(value) => terminal.succeed(value).unit
                  case Exit.Failure(error) =>
                    terminal.fail(error.failureOption.getOrElse(JsonRpc.Error.InternalError)).unit
                }
            }
          }
          .forkIn(scope)
        // A closed scope may interrupt forkIn before its body starts; no wait may be left hanging.
        _ <- fiber.poll.flatMap {
          case Some(exit) =>
            terminal.fail(JsonRpc.Error.InternalError).flatMap { delivered =>
              val log = exit match {
                case Exit.Failure(cause) =>
                  ZIO.logErrorCause("Configured operation stopped before terminal delivery", cause)
                case Exit.Success(_) => ZIO.logError("Configured operation stopped without a terminal result")
              }
              log.when(delivered).unit
            }
          case None => ZIO.unit
        }
        result <- restore(terminal.await)
      } yield result
    }

  private def checked[A](effect: IO[YallmoError, A]): IO[JsonRpc.Error, A] =
    effect.catchAll { error =>
      val response = checkedError(error)
      ZIO
        .logErrorCause("Configured domain operation failed", Cause.fail(error))
        .when(response == JsonRpc.Error.InternalError) *> ZIO.fail(response)
    }

  private def checkedError(error: YallmoError): JsonRpc.Error = error match {
    case YallmoError.StorageFailed(error)                            => checkedError(error)
    case _: YallmoError.SessionNotFound | _: YallmoError.BlobMissing =>
      JsonRpc.Error(ErrorCode.NotFound, "Not found")
    case _: YallmoError.SessionAlreadyExists =>
      JsonRpc.Error(ErrorCode.Conflict, "Session already exists")
    case _: YallmoError.InvalidMessage =>
      JsonRpc.Error(ErrorCode.Rejected, "Invalid message for this invocation")
    case _: YallmoError.InvalidAgentDecision =>
      JsonRpc.Error(ErrorCode.Rejected, "Invocation requires a final answer; use turn.submit for agent actions")
    case YallmoError.BlobUploadFailed(reason) =>
      JsonRpc.Error(ErrorCode.Rejected, reason.message)
    case _: YallmoError.UnknownAgent | _: YallmoError.InvalidConfig =>
      JsonRpc.Error(ErrorCode.Rejected, "Invalid author or assistant configuration")
    case _ => JsonRpc.Error.InternalError
  }
}
