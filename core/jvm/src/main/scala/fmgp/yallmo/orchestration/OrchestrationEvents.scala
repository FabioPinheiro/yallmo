package fmgp.yallmo.orchestration

import fmgp.yallmo.model.{Message, YallmoError}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import zio.*

/** One storage owner's ephemeral fan-out. No history, authoring replay, or cross-process filesystem watch. */
final class OrchestrationEvents private (
    private[orchestration] val mutationGate: Semaphore,
    state: Ref.Synchronized[OrchestrationEvents.State],
    private[orchestration] val executionOrigin: FiberRef[Option[EventOrigin]]
) {
  import OrchestrationEvents.*

  val streamId: String = UUID.randomUUID().toString

  /** Register before reading inventory. Pending writes that began before registration are included explicitly. */
  def subscribe: IO[String, Subscription] = subscribe(EventScope.All)

  def subscribe(scope: EventScope): IO[String, Subscription] =
    for {
      queue <- Queue.dropping[(SequencedEventUpdate, Long)](MaxQueuedEvents)
      failed <- Promise.make[Nothing, String]
      bytes <- Ref.Synchronized.make(0L)
      bootstrap <- Ref.make(Chunk.empty[PendingObservation])
      subscription <- state.modifyZIO { current =>
        val pending = Chunk.fromIterable(current.pending.values).filter(p => accepts(scope, p.origin))
        if current.subscribers.size >= MaxSubscribers || current.untrackedPending > 0
        then ZIO.fail("Event capacity exceeded; resubscribe")
        else {
          val id = UUID.randomUUID().toString
          val subscription = new Subscription(id, current.sequence, scope, bootstrap, queue, failed, bytes, this)
          bootstrap
            .set(pending)
            .as((subscription, current.copy(subscribers = current.subscribers.updated(id, subscription))))
        }
      }
    } yield subscription

  private def remove(id: String): UIO[Unit] =
    state.update(current => current.copy(subscribers = current.subscribers - id))

  private[orchestration] def begin(
      messages: Chunk[Message],
      origin: Option[EventOrigin] = None
  ): UIO[EventUpdate.Pending] =
    state.modify { current =>
      val pending = new EventUpdate.Pending(UUID.randomUUID().toString, messages.map(EventMessage.from))
      val size = publicBytes(pending.messages) + originBytes(origin)
      val overflow = current.pending.size >= MaxPendingEvents || current.pendingBytes + size > MaxPendingBytes
      val next = if overflow then current.copy(untrackedPending = current.untrackedPending + 1)
      else
        current.copy(
          pending = current.pending.updated(pending.operationId, PendingObservation(pending, origin)),
          pendingBytes = current.pendingBytes + size
        )
      (pending, next)
    }

  private[orchestration] def pending(value: EventUpdate.Pending, origin: Option[EventOrigin] = None): UIO[Unit] =
    publish(value, origin)

  private[orchestration] def complete(
      pending: EventUpdate.Pending,
      confirmed: Boolean,
      sessions: Chunk[Session],
      origin: Option[EventOrigin] = None
  ): UIO[Unit] =
    state.updateZIO { current =>
      val sequence = current.sequence + 1
      val change =
        if confirmed then
          EventUpdate.Confirmed(pending.operationId, pending.messages, sessions.map(EventSession.from(_, sequence)))
        else EventUpdate.Uncertain(pending.operationId)
      broadcast(current, SequencedEventUpdate(sequence, change, origin), sessions).as(
        current.copy(
          sequence = sequence,
          pending = current.pending - pending.operationId,
          pendingBytes = current.pendingBytes - current.pending
            .get(pending.operationId)
            .fold(0L)(p => publicBytes(p.change.messages) + originBytes(p.origin)),
          untrackedPending =
            current.untrackedPending - (if current.pending.contains(pending.operationId) then 0L else 1L)
        )
      )
    }

  private def publish(change: EventUpdate, origin: Option[EventOrigin]): UIO[Unit] =
    state.updateZIO { current =>
      val next = current.sequence + 1
      broadcast(current, SequencedEventUpdate(next, change, origin)).as(current.copy(sequence = next))
    }

  private def broadcast(
      current: State,
      event: SequencedEventUpdate,
      sessions: Chunk[Session] = Chunk.empty
  ): UIO[Unit] =
    ZIO.foreachDiscard(current.subscribers.values) { subscriber =>
      val selected = subscriber.scope match {
        case EventScope.All                 => Some(event)
        case scope @ EventScope.Session(id) =>
          event.change match {
            case EventUpdate.Confirmed(operationId, messages, metadata) =>
              val canonical = sessions.filter(_.id == id)
              val pointers = metadata.filter(_.id == id)
              Option.when(accepts(scope, event.origin) || pointers.nonEmpty)(
                event.copy(change =
                  EventUpdate.Confirmed(
                    operationId,
                    (messages.filter(_ => accepts(scope, event.origin)) ++ canonical.flatMap(
                      _.messages.map(EventMessage.from)
                    ))
                      .distinctBy(_.id),
                    pointers
                  )
                )
              )
            case _ => Option.when(accepts(scope, event.origin))(event)
          }
      }
      ZIO.foreachDiscard(selected)(subscriber.offer)
    }

  /** Read snapshots under the same short mutation boundary, not under an agent-turn lock. A revision here dominates
    * buffered pointer events from before the snapshot; messages remain immutable and deduplicate by ID.
    */
  def snapshot[A](read: IO[YallmoError, A]): IO[YallmoError, (A, Long)] =
    mutationGate.withPermit(read.zip(state.get.map(_.sequence)))
}

object OrchestrationEvents {
  val MaxQueuedEvents = 128
  val MaxQueuedBytes: Long = 2L * 1024 * 1024
  val MaxSubscribers = 128
  val MaxPendingEvents = 128
  val MaxPendingBytes: Long = 2L * 1024 * 1024

  private final case class State(
      sequence: Long = 0L,
      pending: Map[String, PendingObservation] = Map.empty,
      pendingBytes: Long = 0L,
      untrackedPending: Long = 0L,
      subscribers: Map[String, Subscription] = Map.empty
  )

  final case class PendingObservation(change: EventUpdate.Pending, origin: Option[EventOrigin])

  private def accepts(scope: EventScope, origin: Option[EventOrigin]): Boolean = scope match {
    case EventScope.All         => true
    case EventScope.Session(id) => origin.exists(_.sessionId == id)
  }

  private def originBytes(origin: Option[EventOrigin]): Long =
    origin.fold(0L)(o =>
      256L + o.sessionId.value.getBytes(UTF_8).length.toLong * 6L +
        o.turnId.fold(0L)(_.getBytes(UTF_8).length.toLong * 6L)
    )

  private def eventBytes(event: SequencedEventUpdate): Long = originBytes(event.origin) + (event.change match {
    case EventUpdate.Pending(_, messages)             => publicBytes(messages)
    case EventUpdate.Confirmed(_, messages, sessions) =>
      publicBytes(messages) + sessions.foldLeft(0L)((n, s) =>
        n + 256L + s.id.value.getBytes(UTF_8).length.toLong * 6L + s.agentIds
          .map(_.toString.getBytes(UTF_8).length.toLong * 6L)
          .sum
      )
    case _: EventUpdate.Uncertain => 256L
  })

  private def publicBytes(messages: Chunk[EventMessage]): Long =
    messages.foldLeft(256L) { (size, message) =>
      // Conservative JSON escaping allowance; private provider continuation payloads are never counted or encoded.
      size + 512L + message.content.getBytes(UTF_8).length.toLong * 6L +
        message.agentId.toString.getBytes(UTF_8).length.toLong * 6L + message.blobIds.size.toLong * 256L +
        message.toolName.fold(0L)(_.toString.getBytes(UTF_8).length.toLong * 6L)
    }

  final class Subscription private[OrchestrationEvents] (
      val id: String,
      val startSequence: Long,
      val scope: EventScope,
      bootstrap: Ref[Chunk[PendingObservation]],
      queue: Queue[(SequencedEventUpdate, Long)],
      failed: Promise[Nothing, String],
      bytes: Ref.Synchronized[Long],
      owner: OrchestrationEvents
  ) {

    private var sequence = startSequence

    /** Bootstrap observations are consumed once, not retained as a subscription-lifetime event journal. */
    def takePending: UIO[Chunk[EventUpdate.Pending]] = takePendingObservations.map(_.map(_.change))

    def takePendingObservations: UIO[Chunk[PendingObservation]] = bootstrap.getAndSet(Chunk.empty)

    private[OrchestrationEvents] def offer(event: SequencedEventUpdate): UIO[Unit] =
      bytes.updateZIO { current =>
        // Called under the owner's serialized state update; filtered-out events consume no local sequence.
        sequence += 1
        val selected = event.copy(sequence = sequence)
        val size = eventBytes(selected)
        if size > MaxQueuedBytes || current + size > MaxQueuedBytes then
          failed.succeed("Event buffer overflow; reconnect and resubscribe").as(current)
        else
          queue.offer((selected, size)).flatMap {
            case true  => ZIO.succeed(current + size)
            case false => failed.succeed("Event buffer overflow; reconnect and resubscribe").as(current)
          }
      }

    def take: IO[String, SequencedEventUpdate] =
      failed.poll.flatMap {
        case Some(reason) => reason.flatMap(ZIO.fail(_))
        case None         =>
          queue.take
            .flatMap { (event, size) => bytes.update(_ - size).as(event) }
            .raceFirst(failed.await.flatMap(ZIO.fail(_)))
      }

    def check: IO[String, Unit] = failed.poll.flatMap {
      case Some(reason) => reason.flatMap(ZIO.fail(_))
      case None         => ZIO.unit
    }

    def close: UIO[Unit] =
      (owner.remove(id) *> failed.succeed("Subscription closed").unit *> bootstrap.set(
        Chunk.empty
      ) *> queue.takeAll.unit).uninterruptible
  }

  private[orchestration] def make: UIO[OrchestrationEvents] =
    for {
      gate <- Semaphore.make(1)
      state <- Ref.Synchronized.make(State())
      origin <- ZIO.scoped(FiberRef.make(Option.empty[EventOrigin]))
    } yield new OrchestrationEvents(gate, state, origin)
}
