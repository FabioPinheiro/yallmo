package fmgp.yallmo.server.application

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.model.llm.ProviderContinuation
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import zio.*

/** In-process session operations with a bound human author and optional configured assistant.
  *
  * This is not an authorized remote API or a run coordinator. Core owns prompt persistence and asynchronous transcript
  * saves; failed or interrupted execution never rolls back accepted input. Selection is immutable and local to this
  * service.
  */
final class SessionSubmission[-R] private (
    orchestrator: Orchestrator[?],
    authorId: AgentId,
    val selectedAgentId: Option[AgentId],
    execution: (Int, Option[Set[ProviderContinuation]]) => Option[AgentExecution[R]]
) {
  def create(name: Option[String]): IO[YallmoError, Session] =
    sessionId(name)
      .flatMap(orchestrator.createSession(_, participantIds))
      .catchSome {
        case YallmoError.StorageFailed(_: YallmoError.SessionAlreadyExists) if name.isEmpty => create(None)
      }

  /** Loading does not change participants or select an assistant. */
  def load(id: String): IO[YallmoError, Session] =
    sessionId(Some(id)).flatMap(orchestrator.loadSession)

  /** Submits through the configured server-owned runner.
    *
    * @param continuationStart
    *   legacy in-process boundary interpreted against each submission's immutable initial history. Callers must
    *   recalculate it after a persisted rewrite; it is not a durable cross-submission reset policy or wire parameter
    */
  def submit(
      previous: Option[String],
      prompt: String,
      continuationStart: Int = 0
  ): ZIO[R, SessionSubmitFailure, SessionTurn] =
    execution(continuationStart, None) match {
      case None             => ZIO.fail(SessionSubmitFailure(YallmoError.InvalidConfig("No agent selected"), None))
      case Some(configured) => submitExecution(previous, prompt, configured)
    }

  /** Server-owned reset identity policy, captured once at reset/selection rather than from later mutable offsets. */
  private[application] def submitExcludingContinuations(
      previous: Option[String],
      prompt: String,
      excluded: Set[ProviderContinuation]
  ): ZIO[R, SessionSubmitFailure, SessionTurn] =
    execution(0, Some(excluded)) match {
      case None             => ZIO.fail(SessionSubmitFailure(YallmoError.InvalidConfig("No agent selected"), None))
      case Some(configured) => submitExecution(previous, prompt, configured)
    }

  /** Trusted local compatibility seam. Remote clients must never supply executable values or authors. */
  def submitExecution[R1](
      previous: Option[String],
      prompt: String,
      configured: AgentExecution[R1]
  ): ZIO[R1, SessionSubmitFailure, SessionTurn] =
    for {
      _ <- ZIO
        .fail(SessionSubmitFailure(YallmoError.InvalidConfig("No matching assistant selected"), None))
        .unless(selectedAgentId.contains(configured.agentId))
      id <- (previous match {
        case Some(value) => sessionId(Some(value))
        case None        => sessionId(None).tap(orchestrator.createSession(_, participantIds))
      }).mapError(SessionSubmitFailure(_, None))
      turn <- orchestrator.submit(id, authorId, AgentTask(prompt), configured)
    } yield turn

  private val participantIds = Chunk(authorId) ++ Chunk.fromIterable(selectedAgentId)

  private def sessionId(name: Option[String]): IO[YallmoError, SessionId] =
    name match {
      case Some(value) => ZIO.fromEither(SessionId.from(value).left.map(YallmoError.InvalidSessionId(value, _)))
      case None        => nextTimestampedSessionId
    }

  private def nextTimestampedSessionId: IO[YallmoError, SessionId] = {
    def next: IO[YallmoError, SessionId] =
      val id = SessionId.unsafe(SessionSubmission.SessionNameFormatter.format(Instant.now()))
      orchestrator.loadSession(id).either.flatMap {
        case Left(YallmoError.StorageFailed(YallmoError.SessionNotFound(_))) => ZIO.succeed(id)
        case Right(_)                                                        => ZIO.sleep(1.second) *> next
        case Left(error)                                                     => ZIO.fail(error)
      }
    next
  }
}

object SessionSubmission {
  private val SessionNameFormatter = DateTimeFormatter.ofPattern("'main-'uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC)

  /** Session-only adapter for existing trusted in-process callers. */
  def make(
      orchestrator: Orchestrator[?],
      authorId: AgentId,
      selectedAgentId: Option[AgentId]
  ): SessionSubmission[Any] =
    new SessionSubmission(orchestrator, authorId, selectedAgentId, (_, _) => None)

  /** Binds execution once from trusted services, without invoking the agent or modifying storage. */
  def configured[R](
      orchestrator: Orchestrator[?],
      authorId: AgentId,
      runner: AgentRunner[R],
      agent: Option[AgentInstance[R]],
      context: StaticContext[R]
  ): SessionSubmission[R] =
    new SessionSubmission(
      orchestrator,
      authorId,
      agent.map(_.id),
      (start, excluded) =>
        agent.map(selected => runner.execution(withCurrentContinuations(selected, start, excluded), context))
    )

  /** Execution construction for legacy in-process REPL constructors; not a client/protocol operation. The offset is
    * interpreted against each submission's initial history, so legacy callers must recalculate after persisted rebase.
    */
  def configuredExecution[R](
      runner: AgentRunner[R],
      agent: AgentInstance[R],
      context: StaticContext[R],
      continuationStart: Int
  ): AgentExecution[R] =
    runner.execution(withCurrentContinuations(agent, continuationStart), context)

  /** A branch starts a fresh provider continuation scope. Retain initial stored objects, but exclude their opaque
    * continuation payloads from provider requests; reasoning generated during this execution remains usable.
    */
  private[application] def branchExecution[R](
      runner: AgentRunner[R],
      agent: AgentInstance[R],
      context: StaticContext[R]
  ): AgentExecution[R] =
    runner.execution(withCurrentContinuations(agent, Int.MaxValue), context)

  /** Filter only provider request history; keep the runner's append-only transcript and capability policy intact. */
  private def withCurrentContinuations[R](
      selected: AgentInstance[R],
      start: Int,
      excluded: Option[Set[ProviderContinuation]] = None
  ): AgentInstance[R] =
    if start <= 0 && excluded.forall(_.isEmpty) then selected
    else
      new AgentInstance[R] {
        override val id: AgentId = selected.id
        override val capabilities = selected.capabilities
        override def configuredContext(context: StaticContext[R]): ZIO[R, YallmoError, StaticContext[R]] =
          selected.configuredContext(context)
        override def nextManaged(
            task: AgentTask,
            context: StaticContext[R],
            current: RunContext,
            history: ManagedHistory
        ): ZIO[R, YallmoError, AgentTurn] =
          for {
            old <- excluded match {
              case Some(values) => ZIO.succeed(values)
              case None => history.initialMessages.map(initial => continuations(initial.take(start).map(_.payload)))
            }
            turn <- selected.nextManaged(task, context, withoutContinuations(current, old), history)
          } yield turn

        override def next(
            task: AgentTask,
            context: StaticContext[R],
            current: RunContext
        ): ZIO[R, YallmoError, AgentTurn] =
          selected.next(task, context, excluded.fold(requestSnapshot(current))(withoutContinuations(current, _)))

        private def requestSnapshot(current: RunContext): RunContext = {
          val messages = current.messages.zipWithIndex.collect {
            case (message, index) if index >= start || !message.isInstanceOf[MessagePayload.Reasoning] => message
          }
          current.copy(messages = messages)
        }
      }

  private[application] def continuations(messages: Chunk[MessagePayload]): Set[ProviderContinuation] =
    messages.collect { case MessagePayload.Reasoning(_, continuation) => continuation }.toSet

  private def withoutContinuations(current: RunContext, excluded: Set[ProviderContinuation]): RunContext =
    current.copy(messages = current.messages.filter {
      case MessagePayload.Reasoning(_, continuation) => !excluded(continuation)
      case _                                         => true
    })
}
