package fmgp.yallmo.view.web

import fmgp.yallmo.model.MessageId
import fmgp.yallmo.orchestration.{EventSession, SessionId}
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import zio.Chunk
import zio.json.*

private[web] final case class ChatFeedState(
    snapshot: Option[SessionSnapshot] = None,
    pending: List[MessageSnapshot] = Nil,
    loading: Boolean = true,
    error: Option[String] = None,
    baseline: Boolean = false
)

/** A single session scope. Provenance routes observations; only revisioned head ancestry establishes membership. */
private[web] final class ChatSessionFeed(
    id: SessionId,
    subscribe: (SubscriptionScope, EventsUpdate.Params => Unit) => EventSubscription,
    publish: ChatFeedState => Unit
)(using ExecutionContext) {
  import EventSubscription.{MaxBufferedBytes, MaxBufferedEvents}
  private var epoch = 0L
  private var handle = Option.empty[EventSubscription]
  private var objects = Map.empty[MessageId, MessageSnapshot]
  private var canonical = Option.empty[EventSession]
  private var observations = Map.empty[String, List[MessageSnapshot]]
  private var last = ChatFeedState()
  private val scope = SubscriptionScope.Session(id)

  def stop(): Unit = {
    epoch += 1
    val previous = handle
    handle = None
    previous.foreach(_.cancel())
  }

  private def emit(baseline: Boolean = false): Boolean = {
    var next = canonical.flatMap(_.head)
    var seen = Set.empty[MessageId]
    var chain = List.empty[MessageSnapshot]
    while next.nonEmpty && !seen.contains(next.get) && objects.contains(next.get) do {
      val message = objects(next.get)
      seen += message.id
      chain = message :: chain
      next = message.parentId
    }
    val complete = next.isEmpty && canonical.forall(s => s.error.isEmpty && s.messageCount == chain.size.toLong)
    if complete then {
      val snapshot = canonical.map(s => SessionSnapshot(id, s.agentIds, Chunk.fromIterable(chain)))
      // Stored objects off the canonical chain are not transcript messages, even if this turn produced them.
      val pending = observations.valuesIterator.flatten.toList
        .distinctBy(_.id)
        .filterNot(m => seen.contains(m.id))
        .sortBy(m => (m.timestamp.toString, MessageId.value(m.id)))
      last = ChatFeedState(snapshot, pending, loading = false, baseline = baseline)
      publish(last)
    }
    complete
  }

  def start(): Unit = start(1)
  private def start(recoveries: Int): Unit = {
    stop()
    val current = epoch
    def valid: Boolean = current == epoch
    var baseline = Option.empty[EventsSubscribe.Result]
    var early = Vector.empty[EventsUpdate.Params]
    var earlyBytes = 0L
    var sequence = 0L
    def invalid(reason: String): Unit = if valid then {
      stop()
      observations = Map.empty
      last = last.copy(pending = Nil, loading = false, error = Some(reason))
      publish(last)
      if recoveries > 0 then start(recoveries - 1)
    }
    def bounded: Boolean = observations.size <= MaxBufferedEvents &&
      observations.iterator.map { case (operation, messages) =>
        EventPending(operation, Chunk.fromIterable(messages)).toJson.getBytes(UTF_8).length.toLong
      }.sum <= MaxBufferedBytes
    def applyEvent(event: EventsUpdate.Params): Unit = if valid then
      baseline.foreach { initial =>
        if event.subscriptionId != initial.subscriptionId || event.streamId != initial.streamId || event.nonce != initial.nonce
        then invalid("Chat stream changed. Reload history to recover.")
        else if event.sequence > sequence then {
          if event.sequence != sequence + 1 then invalid("Chat event gap. Reload history to recover.")
          else {
            sequence = event.sequence
            event.change match {
              case EventChange.Pending(operation, messages) =>
                if event.origin.exists(_.sessionId == id) then observations += operation -> messages.toList
                if !bounded then invalid("Chat pending buffer overflow.")
                else if !emit() then invalid("Incomplete canonical Chat ancestry.")
              case EventChange.Confirmed(operation, messages, sessions) =>
                objects ++= messages.map(m => m.id -> m)
                if event.origin.exists(_.sessionId == id) then
                  observations += operation -> (observations.getOrElse(operation, Nil) ++ messages).distinctBy(_.id)
                sessions.filter(_.id == id).foreach { session =>
                  if canonical.forall(_.revision < session.revision) then canonical = Some(session)
                }
                if sessions.exists(_.id == id) then {
                  // A pointer outcome excludes sibling branches, but preserves provisional descendants of the new head.
                  val candidates = observations.valuesIterator.flatten.map(m => m.id -> m).toMap
                  val head = canonical.flatMap(_.head)
                  def followsHead(message: MessageSnapshot): Boolean = {
                    var parent: Option[MessageId] = Some(message.id)
                    var visited = Set.empty[MessageId]
                    while parent != head && parent.nonEmpty && !visited.contains(parent.get) && candidates.contains(
                        parent.get
                      )
                    do {
                      visited += parent.get
                      parent = candidates(parent.get).parentId
                    }
                    parent == head && !head.contains(message.id)
                  }
                  observations = observations.view.mapValues(_.filter(followsHead)).toMap.filter(_._2.nonEmpty)
                }
                if !bounded then invalid("Chat publication buffer overflow.")
                else if !emit() then invalid("Incomplete canonical Chat ancestry.")
              case EventChange.Uncertain(_) => invalid("Chat outcome uncertain. Review history before resending.")
            }
          }
        }
      }
    last = last.copy(pending = Nil, loading = true, baseline = false)
    publish(last)
    val subscription = subscribe(
      scope,
      event =>
        if valid then
          baseline match {
            case Some(_) => applyEvent(event)
            case None    =>
              earlyBytes += event.toJson.getBytes(UTF_8).length.toLong
              if early.size >= MaxBufferedEvents || earlyBytes > MaxBufferedBytes
              then invalid("Chat bootstrap buffer overflow.")
              else early :+= event
          }
    )
    if valid then handle = Some(subscription) else subscription.cancel()
    subscription.result.onComplete {
      case Success(initial) if valid =>
        if initial.scope != scope || initial.subscriptionId.isEmpty || initial.streamId.isEmpty || initial.startSequence < 0 ||
          initial.pending.size > MaxBufferedEvents ||
          initial.toJson.getBytes(UTF_8).length > MaxBufferedBytes ||
          initial.sessions.exists(_.id != id)
        then invalid("Invalid Chat baseline.")
        else {
          baseline = Some(initial)
          sequence = initial.startSequence
          objects = initial.messages.map(m => m.id -> m).toMap
          canonical = initial.sessions.headOption
          observations = initial.pending
            .filter(_.origin.exists(_.sessionId == id))
            .map(p => p.operationId -> p.messages.toList)
            .toMap
          if !bounded || !emit(baseline = true) then invalid("Incomplete canonical Chat baseline.")
          else early.foreach(applyEvent)
          early = Vector.empty
        }
      case Failure(error) if valid =>
        stop()
        observations = Map.empty
        last = last.copy(
          pending = Nil,
          loading = false,
          error = Some(Option(error.getMessage).getOrElse("Chat subscription failed"))
        )
        publish(last)
      case _ => ()
    }
  }
}
