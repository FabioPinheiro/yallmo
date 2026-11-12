package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.storage.{StorageFS, StorageEvents, StorageInMemory, StoreageUtils}
import java.nio.file.Path
import zio.*

/** Default [[Orchestrator]] implementation backed by an internal [[Storage]]. */
final class OrchestratorLive[-R] private[orchestration] (
    val storage: Storage,
    val catalog: AgentCatalog[R],
    val events: OrchestrationEvents
) extends Orchestrator[R] {

  override def getAgent(id: AgentId): IO[YallmoError, Agent[R]] =
    catalog.getAgent(id)

  def listAgentIds: IO[YallmoError, Chunk[AgentId]] =
    catalog.listAgentIds

  def listMessages: IO[YallmoError, Chunk[Message]] =
    storage.listMessages.mapError(YallmoError.StorageFailed.apply)

  def listBlobIds: IO[YallmoError, Chunk[BlobId]] =
    storage.listBlobIds.mapError(YallmoError.StorageFailed.apply)

  override def listSessions: IO[YallmoError, Chunk[SessionSummary]] =
    storage.listSessions.mapError(YallmoError.StorageFailed.apply)

  @deprecated("Use submit for session-aware agent execution and persistence.", "2.0.0")
  override def turn(id: AgentId, request: AgentTurnRequest): ZIO[R, YallmoError, AgentTurnResponse] =
    getAgent(id).flatMap(_.turn(request.copy(readBlob = id => readBlob(id))))

  override def submit[R1](
      id: SessionId,
      userId: AgentId,
      task: AgentTask,
      execution: AgentExecution[R1]
  ): ZIO[R1, SessionSubmitFailure, SessionTurn] =
    ZIO.suspendSucceed {
      val origin = EventOrigin(id, Some(java.util.UUID.randomUUID().toString))
      events.executionOrigin.locally(Some(origin))(
        for {
          // Await prompt persistence before execution; Storage owns interruption safety for its critical writes.
          accepted <- (for {
            _ <- getAgent(execution.agentId)
            _ <- addSessionAgent(id, userId)
            session <- append(id, userId, Chunk(MessagePayload.User(task.prompt)))
          } yield session).mapError(SessionSubmitFailure(_, None))
          submitted <- managedSubmit(accepted.messages, Some(accepted), task, execution)
            .mapError(failure => SessionSubmitFailure(failure.error, failure.session))
        } yield SessionTurn(
          submitted.session.getOrElse(accepted),
          submitted.result,
          Chunk(MessagePayload.User(task.prompt)) ++ submitted.appended.map(_.payload)
        )
      )
    }

  override def submitBranch[R1](
      parentId: MessageId,
      authorId: AgentId,
      task: AgentTask,
      execution: AgentExecution[R1]
  ): ZIO[R1, BranchSubmitFailure, BranchTurn] =
    events.executionOrigin.locally(None)(for {
      source <- (for {
        _ <- ZIO.fail(YallmoError.InvalidMessage("Prompt must not be blank")).when(task.prompt.trim.isEmpty)
        _ <- getAgent(execution.agentId)
        _ <- catalog.validateAuthor(authorId)
        inventory <- storage.listMessages.mapError(YallmoError.StorageFailed.apply)
        history <- StoreageUtils.ancestry(inventory.map(message => message.id -> message).toMap, Some(parentId))
        _ <- validateToolExchanges(history)
      } yield history).mapError(error => BranchSubmitFailure(error, BranchAcceptance.NotAccepted))
      timestamp <- Clock.instant
      prompt = Message(MessageData(authorId, Some(parentId), timestamp, MessagePayload.User(task.prompt)))
      _ <- persistMessage(prompt).mapError(error =>
        BranchSubmitFailure(error, BranchAcceptance.Unknown, Some(prompt.id))
      )
      result <- managedSubmit(source :+ prompt, None, task, execution).foldZIO(
        failure => verifyBranch(source, prompt, failure.messages, failure.appended, Left(failure.error)),
        turn => verifyBranch(source, prompt, turn.messages, turn.appended, Right(turn.result))
      )
    } yield result)

  private def verifyBranch(
      source: Chunk[Message],
      prompt: Message,
      active: Chunk[Message],
      audit: Chunk[Message],
      outcome: Either[YallmoError, AgentResult]
  ): IO[BranchSubmitFailure, BranchTurn] = {
    val knownFailure = (error: YallmoError) =>
      BranchSubmitFailure(error, BranchAcceptance.Accepted, Some(prompt.id), Some(prompt.id), Chunk(prompt))
    for {
      inventory <- storage.listMessages.mapError(error => knownFailure(YallmoError.StorageFailed(error)))
      objects = inventory.map(message => message.id -> message).toMap
      sourceIds = source.map(_.id).toSet
      candidates = (Chunk(prompt) ++ audit ++ active.filterNot(message => sourceIds(message.id))).distinctBy(_.id)
      saved = candidates.filter(message => objects.get(message.id).contains(message))
      head = active
        .takeWhile(message => objects.get(message.id).contains(message))
        .lastOption
        .map(_.id)
        .getOrElse(prompt.id)
      result <- outcome match {
        case Left(error) =>
          ZIO.fail(BranchSubmitFailure(error, BranchAcceptance.Accepted, Some(prompt.id), Some(head), saved))
        case Right(_) if saved.size != candidates.size =>
          ZIO.fail(
            BranchSubmitFailure(
              YallmoError.InvalidMessage("Branch transcript was not fully persisted"),
              BranchAcceptance.Accepted,
              Some(prompt.id),
              Some(head),
              saved
            )
          )
        case Right(result) => ZIO.succeed(BranchTurn(prompt.parentId.get, prompt.id, head, result, saved))
      }
    } yield result
  }

  private final case class ManagedTurn(
      session: Option[Session],
      result: AgentResult,
      messages: Chunk[Message],
      appended: Chunk[Message]
  )
  private final case class ManagedFailure(
      error: YallmoError,
      session: Option[Session],
      messages: Chunk[Message],
      appended: Chunk[Message]
  )

  private def managedSubmit[R1](
      initial: Chunk[Message],
      publication: Option[Session],
      task: AgentTask,
      execution: AgentExecution[R1]
  ): ZIO[R1, ManagedFailure, ManagedTurn] = {
    val label = publication.fold("Unnamed branch")(session => s"Session ${session.id.value}")
    ZIO.scoped {
      for {
        active <- Ref.make(initial)
        closed <- Ref.make(false)
        gate <- Semaphore.make(1)
        // This journal NEVER changes prefixes, even when active context is replaced.
        journal <- Ref.make(initial)
        audit <- Ref.make(Chunk.empty[Message])
        storedCount <- Ref.make(initial.size)
        published <- Ref.make(publication)
        attemptedPublication <- Ref.make(initial)
        queue <- Queue.unbounded[Option[UIO[Unit]]]
        persistObjects =
          for {
            all <- journal.get
            count <- storedCount.get
            _ <- ZIO.foreachDiscard(all.drop(count))(message => persistMessage(message) *> storedCount.update(_ + 1))
          } yield ()
        publish = (next: Chunk[Message]) =>
          publication match {
            case None           => ZIO.unit
            case Some(accepted) =>
              val id = accepted.id
              val target = next.lastOption.map(_.id)
              for {
                pending <- attemptedPublication.get
                _ <- storage.loadSession(id).exit.flatMap {
                  case Exit.Success(saved) if saved.messages == pending => published.set(Some(saved))
                  case _                                                => ZIO.unit
                }
                previous <- published.get.map(_.getOrElse(accepted))
                expected = previous.messages.lastOption.map(_.id)
                _ <- attemptedPublication.set(next)
                _ <- (ZIO.foreachDiscard(next.map(_.agentId).distinct)(addSessionAgent(id, _)) *>
                  storage
                    .compareAndSetSessionHead(id, expected, target)
                    .flatMap(saved =>
                      published.set(Some(saved)) *> ZIO
                        .logDebug(s"Session pointer published session=${id.value} old=$expected new=$target")
                    )).catchAllCause { _ =>
                  // A ref may have succeeded before its acknowledgement failed. Recognize ONLY the exact target;
                  // never adopt a competing head as the next expected parent.
                  ZIO.logError(s"Session pointer publication failed session=${id.value} old=$expected new=$target") *>
                    storage.loadSession(id).exit.flatMap {
                      case Exit.Success(saved) if saved.messages == next => published.set(Some(saved))
                      case _                                             => ZIO.unit
                    }
                }
              } yield ()
          }
        checkpoint = (next: Chunk[Message]) =>
          (persistObjects *> publish(next)).catchAllCause(cause =>
            ZIO.logErrorCause(s"$label checkpoint persistence failed", cause)
          )
        writer <- queue.take
          .flatMap {
            case Some(work) => work.as(true)
            case None       => ZIO.succeed(false)
          }
          .repeatWhile(identity)
          .forkScoped
        history = new ManagedHistory {
          override def messages: UIO[Chunk[Message]] = active.get
          override def initialMessages: UIO[Chunk[Message]] = ZIO.succeed(initial)
          override private[orchestration] def snapshot: UIO[Chunk[Message]] = active.get
          private def ensureOpen: IO[YallmoError, Unit] = closed.get.flatMap(value =>
            ZIO.fail(YallmoError.InvalidAgentDecision("Managed history submission is closed")).when(value).unit
          )
          private def allocate(payloads: Chunk[MessagePayload], parent: Option[MessageId]): UIO[Chunk[Message]] =
            ZIO.foldLeft(payloads)(Chunk.empty[Message]) { (batch, payload) =>
              Clock.instant.map(timestamp =>
                batch :+ Message(
                  MessageData(execution.agentId, batch.lastOption.map(_.id).orElse(parent), timestamp, payload)
                )
              )
            }
          private def appendLocked(payloads: Chunk[MessagePayload]): IO[YallmoError, Option[MessageId]] = for {
            _ <- ensureOpen
            current <- active.get
            batch <- allocate(payloads, current.lastOption.map(_.id))
            next = current ++ batch
            _ <- journal.update(_ ++ batch)
            _ <- audit.update(_ ++ batch)
            _ <- active.set(next)
            _ <- queue.offer(Some(checkpoint(next)))
          } yield next.lastOption.map(_.id)
          override private[orchestration] def append(payloads: Chunk[MessagePayload]): IO[YallmoError, Unit] =
            gate.withPermit(appendLocked(payloads).unit.uninterruptible)
          override private[orchestration] def appendAt(
              payloads: Chunk[MessagePayload],
              expectedParent: Option[MessageId]
          ): IO[YallmoError, Option[MessageId]] = gate.withPermit {
            (for {
              _ <- ensureOpen
              current <- active.get
              head <-
                if current.lastOption.map(_.id) == expectedParent then appendLocked(payloads)
                else
                  for {
                    // Preserve actual stale execution under the parent it used; never attach it to a new context.
                    batch <- allocate(payloads, expectedParent)
                    _ <- journal.update(_ ++ batch)
                    _ <- audit.update(_ ++ batch)
                    _ <- queue.offer(Some(checkpoint(current)))
                    _ <- ZIO.fail(
                      YallmoError.InvalidAgentDecision(
                        "History changed during work; stale execution retained, continuation stopped"
                      )
                    )
                  } yield current.lastOption.map(_.id)
            } yield head).uninterruptible
          }
          override def rebase(
              base: MessageId,
              head: MessageId,
              onto: Option[MessageId]
          ): IO[YallmoError, Chunk[Message]] =
            gate.withPermit(rebaseLocked(base, head, onto))

          private def rebaseLocked(
              base: MessageId,
              head: MessageId,
              onto: Option[MessageId]
          ): IO[YallmoError, Chunk[Message]] =
            for {
              _ <- ensureOpen
              current <- active.get
              _ <- ZIO
                .fail(YallmoError.InvalidMessage("Managed rebase must end at the current local head"))
                .unless(current.lastOption.exists(_.id == head) && current.exists(_.id == base))
              barrier <- Promise.make[YallmoError, Unit]
              _ <- queue.offer(Some(persistObjects.exit.flatMap(barrier.done).unit))
              _ <- barrier.await
              inventory <- storage.listMessages
              objects = inventory.map(m => m.id -> m).toMap
              source <- StoreageUtils.ancestry(objects, Some(head))
              destination <- StoreageUtils.ancestry(objects, onto)
              _ <- ZIO.foreachDiscard(source ++ destination)(m => catalog.validateAuthor(m.agentId))
              originals = source.drop(source.indexWhere(_.id == base) + 1)
              changed = destination ++ originals != current
              // Each side of the cut must stand alone; never repair or re-pair an exchange across a cut.
              _ <- ZIO.when(changed) {
                for {
                  _ <- validateToolExchanges(destination)
                  _ <- validateToolExchanges(originals)
                  _ <- validateOriginalToolPairs(source, originals)
                } yield ()
              }
              retainedDestination = if changed then destination.filterNot(isReasoning) else destination
              retainedSource = if changed then originals.filterNot(isReasoning) else originals
              next = if changed then replayMessages(retainedDestination ++ retainedSource) else current
              rewritten = next.drop(retainedDestination.size)
              _ <- validateToolExchanges(next).when(changed)
              _ <- ZIO.foreachDiscard(next)(persistMessage)
              removed = if changed then (destination ++ originals).filter(isReasoning).map(_.id) else Chunk.empty
              mappings = (retainedDestination ++ retainedSource).map(_.id).zip(next.map(_.id))
              // Short interruption-safe activation/enqueue; all object I/O above remains interruptible.
              _ <- (active.set(next) *>
                queue.offer(Some(publish(next))) *>
                ZIO.logInfo(
                  s"Managed rebase target=$label base=$base head=$head onto=$onto old=${current.lastOption.map(_.id)} new=${next.lastOption.map(_.id)} copies=$mappings removedReasoning=$removed"
                )).uninterruptible
            } yield rewritten
        }
        // Close under the same local gate: a retained capability cannot enqueue into a dead writer.
        finish = gate.withPermit(
          closed.set(true) *> active.get.flatMap(next => queue.offer(Some(checkpoint(next))))
        ) *>
          queue.offer(None) *> writer.join *> journal.get.zip(storedCount.get).flatMap { case (all, count) =>
            ZIO
              .logWarning(s"$label submission has ${all.size - count} generated message(s) not stored")
              .when(count < all.size)
              .unit
          }
        turn <- (for {
          turn <- execution.run(task, history)
          _ <- ZIO
            .fail(YallmoError.InvalidAgentDecision("Agent execution must preserve its agent ID"))
            .unless(turn.result.agentId == execution.agentId)
        } yield turn)
          .ensuring(finish)
          .catchAllCause { cause =>
            val typed = cause.failureOption.filter(_ => cause.defects.isEmpty && !cause.isInterrupted)
            val error = typed.orElse(
              Option.when(publication.isEmpty)(
                YallmoError.InvalidAgentDecision("Branch execution interrupted or failed unexpectedly")
              )
            )
            error match {
              case Some(value) =>
                (ZIO.logErrorCause("Branch execution failed unexpectedly", cause).when(typed.isEmpty) *>
                  (for {
                    saved <- published.get
                    messages <- active.get
                    appended <- audit.get
                    failed <- ZIO.fail(ManagedFailure(value, saved, messages, appended))
                  } yield failed))
              case None => ZIO.refailCause(cause.stripFailures)
            }
          }
        saved <- published.get
        response <- audit.get
        messages <- active.get
      } yield ManagedTurn(saved, turn.result, messages, response)

    }
  }

  private def persistMessage(message: Message): IO[YallmoError, Message] =
    catalog.validateAuthor(message.agentId) *>
      storage.putMessage(message).mapError(YallmoError.StorageFailed.apply)

  private def isReasoning(message: Message): Boolean = message.payload.isInstanceOf[MessagePayload.Reasoning]

  private def replayMessages(originals: Chunk[Message]): Chunk[Message] =
    originals.foldLeft(Chunk.empty[Message]) { (chain, original) =>
      chain :+ Message(original.data.copy(parentId = chain.lastOption.map(_.id)))
    }

  /** Complete independent exchanges only. Call IDs are unique throughout each validated chain. */
  private def validateToolExchanges(messages: Chunk[Message]): IO[YallmoError, Unit] = ZIO.fromEither {
    messages
      .foldLeft[Either[YallmoError, (Map[FunctionCallId, FunctionCall], Set[FunctionCallId])]](
        Right((Map.empty, Set.empty))
      ) { (state, message) =>
        state.flatMap { case (calls, outputs) =>
          def invalid = Left(YallmoError.InvalidMessage("Invalid or incomplete tool exchange in managed rebase"))
          message.payload match {
            case MessagePayload.ToolCall(call) =>
              if calls.contains(call.id) || call.id.value.trim.isEmpty || call.validate.isLeft then invalid
              else Right((calls.updated(call.id, call), outputs))
            case MessagePayload.ToolOutput(output) =>
              if outputs(output.callId) || !calls.get(output.callId).exists(_.name == output.name) ||
                output.validate.isLeft
              then invalid
              else Right((calls, outputs + output.callId))
            case _ => Right((calls, outputs))
          }
        }
      }
      .flatMap { case (calls, outputs) =>
        Either.cond(
          calls.keySet == outputs,
          (),
          YallmoError.InvalidMessage("Incomplete tool exchange in managed rebase")
        )
      }
  }

  // A retained output must still refer to its unique original call, not a later same-ID replacement.
  private def validateOriginalToolPairs(source: Chunk[Message], retained: Chunk[Message]): IO[YallmoError, Unit] =
    ZIO.foreachDiscard(retained) { message =>
      message.payload match {
        case MessagePayload.ToolOutput(output) =>
          val preceding = source.takeWhile(_.id != message.id).filter {
            _.payload match {
              case MessagePayload.ToolCall(call) => call.id == output.callId
              case _                             => false
            }
          }
          ZIO
            .fail(YallmoError.InvalidMessage("Ambiguous original tool pairing in managed rebase"))
            .unless(preceding.size == 1 && retained.exists(_.id == preceding.head.id))
            .unit
        case _ => ZIO.unit
      }
    }

  override def rebase(
      base: MessageId,
      head: MessageId,
      onto: Option[MessageId]
  ): IO[YallmoError, Chunk[Message]] =
    for {
      // Immutable objects only grow. Snapshot once; never infer ancestry from list/timestamp/session order.
      stored <- storage.listMessages.mapError(YallmoError.StorageFailed.apply)
      messages = stored.map(message => message.id -> message).toMap
      checked = (id: MessageId) =>
        for {
          message <- ZIO
            .fromOption(messages.get(id))
            .orElseFail(YallmoError.InvalidMessage(s"Rebase message ${MessageId.value(id)} is missing"))
          _ <- ZIO
            .fail(YallmoError.InvalidMessage(s"Rebase message ${MessageId.value(id)} has an invalid hash"))
            .unless(message.id == MessageId.from(message.data))
          _ <- catalog.validateAuthor(message.agentId)
        } yield message
      ancestry = (id: MessageId) => {
        def loop(
            current: Option[MessageId],
            seen: Set[MessageId],
            chain: List[Message]
        ): IO[YallmoError, Chunk[Message]] = ZIO.suspendSucceed {
          current match {
            case None                              => ZIO.succeed(Chunk.fromIterable(chain))
            case Some(next) if seen.contains(next) =>
              ZIO.fail(YallmoError.InvalidMessage("Rebase ancestry contains a cycle"))
            case Some(next) =>
              checked(next).flatMap(message => loop(message.parentId, seen + next, message :: chain))
          }
        }
        loop(Some(id), Set.empty, Nil)
      }
      _ <- checked(base)
      source <- ancestry(head)
      baseIndex = source.indexWhere(_.id == base)
      _ <- ZIO
        .fail(YallmoError.InvalidMessage("Rebase base is not an ancestor of head"))
        .when(baseIndex < 0)
      _ <- ZIO.foreachDiscard(onto)(ancestry)
      // All validation precedes the first put. A failed/interrupted put may leave a saved prefix, never a ref change.
      rewritten <- ZIO.foldLeft(source.drop(baseIndex + 1))(Chunk.empty[Message]) { (saved, original) =>
        val parent = saved.lastOption.map(_.id).orElse(onto)
        val message = Message(original.data.copy(parentId = parent))
        storage.putMessage(message).mapError(YallmoError.StorageFailed.apply).as(saved :+ message)
      }
    } yield rewritten

  override def invoke[R1](input: Message, execution: AgentInvocation[R1]): ZIO[R1, YallmoError, Message] =
    events.executionOrigin.locally(None)(for {
      _ <- ZIO
        .fail(YallmoError.InvalidMessage("Message id does not match data"))
        .unless(input.id == MessageId.from(input.data))
      _ <- getAgent(execution.agentId)
      accepted <- persistMessage(input)
      _ <- ZIO.foreachDiscard(accepted.payload.blobIds.distinct)(id => readBlob(id, BlobUploadLimits.AwaitTimeout))
      payload <- execution.invoke(accepted.payload)
      timestamp <- Clock.instant
      response <- persistMessage(
        Message(
          MessageData(
            execution.agentId,
            Some(accepted.id),
            timestamp,
            payload
          )
        )
      )
    } yield response)

  override def createSession(id: SessionId, agentIds: Chunk[AgentId]): IO[YallmoError, Session] =
    validateAuthors(agentIds) *> storage.createSession(id, agentIds).mapError(YallmoError.StorageFailed.apply)

  override def loadSession(id: SessionId): IO[YallmoError, Session] =
    storage.loadSession(id).mapError(YallmoError.StorageFailed.apply)

  override def loadSessionParcial(id: SessionId): IO[YallmoError, SessionParcial] =
    storage.loadSessionParcial(id).mapError(YallmoError.StorageFailed.apply)

  override def addSessionAgent(id: SessionId, agentId: AgentId): IO[YallmoError, Session] =
    catalog.validateAuthor(agentId) *> storage.addSessionAgent(id, agentId).mapError(YallmoError.StorageFailed.apply)

  override def append(
      id: SessionId,
      agentId: AgentId,
      messages: Chunk[MessagePayload]
  ): IO[YallmoError, Session] =
    catalog.validateAuthor(agentId) *>
      (if messages.isEmpty then ZIO.fail(YallmoError.EmptyAppend(id))
       else appendToLatestSession(id, agentId, messages)).mapError(YallmoError.StorageFailed.apply)

  private def appendToLatestSession(
      id: SessionId,
      agentId: AgentId,
      payloads: Chunk[MessagePayload]
  ): IO[YallmoError, Session] =
    (for {
      session <- storage.loadSession(id)
      _ <- ZIO.fail(YallmoError.AgentNotInSession(id, agentId)).unless(session.agentIds.contains(agentId))
      expectedHead = session.messages.lastOption.map(_.id)
      messages <- ZIO.foldLeft(payloads)(Chunk.empty[Message]) { (batch, payload) =>
        Clock.instant.map { timestamp =>
          val parent = batch.lastOption.map(_.id).orElse(expectedHead)
          batch :+ Message(MessageData(agentId, parent, timestamp, payload))
        }
      }
      saved <- storage.putMessagesAndUpdateSession(id, expectedHead, messages)
    } yield saved).catchSome {
      // Only this pre-write rejection is safe to retry. Rebuild timestamps/parents from the latest snapshot.
      case _: YallmoError.SessionHeadConflict => ZIO.yieldNow *> appendToLatestSession(id, agentId, payloads)
    }

  override def readBlob(blobId: BlobId, awaitTimeout: Duration = Duration.Zero): IO[YallmoError, Blob] =
    storage.readBlob(blobId, awaitTimeout)

  override def beginBlobUpload(blobId: BlobId, totalBytes: Long): IO[YallmoError, BlobUploader] =
    storage.beginBlobUpload(blobId, totalBytes)

  override def appendWithBlobWriter[R1](
      id: SessionId,
      agentId: AgentId,
      message: MessagePayload,
      produce: (Chunk[Byte] => IO[YallmoError, Unit]) => ZIO[R1, YallmoError, Unit]
  ): ZIO[R1, YallmoError, Session] =
    for {
      _ <- catalog.validateAuthor(agentId)
      session <- loadSession(id)
      _ <- ZIO.fail(YallmoError.AgentNotInSession(id, agentId)).unless(session.agentIds.contains(agentId))
      blobId <- message.blobIds.toList match {
        case blobId :: Nil => ZIO.succeed(blobId)
        case _             => ZIO.fail(YallmoError.InvalidConfig("Blob append requires exactly one blob ID"))
      }
      result <- ZIO.uninterruptibleMask { restore =>
        for {
          // A closed buffer also closes the scoped writer. Each write and close is atomic.
          buffer <- Ref.make[Option[Chunk[Byte]]](Some(Chunk.empty))
          write = (bytes: Chunk[Byte]) =>
            buffer
              .modify {
                case Some(current) => (Right(()), Some(current ++ bytes))
                case None          => (Left(YallmoError.BlobWriterClosed(blobId)), None)
              }
              .flatMap(ZIO.fromEither)
          exit <- (append(id, agentId, Chunk(message)) <* restore(ZIO.suspendSucceed(produce(write)))).exit
          bytes <- buffer.getAndSet(None).map(_.getOrElse(Chunk.empty))
          _ <- storage.putBlob(Blob(blobId, bytes))
          appended <- exit
        } yield appended
      }
    } yield result

  override def fork(source: SessionId, target: SessionId): IO[YallmoError, Session] =
    storage.fork(source, target).mapError(YallmoError.StorageFailed.apply)

  private def validateAuthors(agentIds: Chunk[AgentId]): IO[YallmoError, Unit] =
    ZIO.foreachDiscard(agentIds)(catalog.validateAuthor)
}

object OrchestratorLive {

  private[orchestration] def make[R](storage: Storage, catalog: AgentCatalog[R]): UIO[OrchestratorLive[R]] =
    OrchestrationEvents.make.map(updates => new OrchestratorLive(new StorageEvents(storage, updates), catalog, updates))

  /** Creates an orchestrator using a shared registry and ephemeral, process-local storage. */
  def inMemory[R](
      catalog: AgentCatalog[R]
  ): UIO[OrchestratorLive[R]] =
    for {
      storage <- StorageInMemory.make
      orchestrator <- make(storage, catalog)
    } yield orchestrator

  /** Opens filesystem storage using a shared registry. */
  def filesystem[R](
      root: Path,
      catalog: AgentCatalog[R]
  ): IO[YallmoError, OrchestratorLive[R]] =
    for {
      storage <- StorageFS.open(root)
      orchestrator <- make(storage, catalog)
    } yield orchestrator

}
