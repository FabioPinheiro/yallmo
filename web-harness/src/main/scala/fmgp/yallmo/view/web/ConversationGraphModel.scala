package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.Var
import fmgp.yallmo.model.MessageId
import fmgp.yallmo.orchestration.{EventSession as PublishedSession, SessionSummary}
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import zio.Chunk
import zio.json.*

private[web] final case class GraphSessionNode(id: String, head: Option[String], error: Option[String] = None)
private[web] final case class GraphSnapshot(messages: List[MessageSnapshot], sessions: List[GraphSessionNode])
private[web] final case class GraphLoadState(
    snapshot: Option[GraphSnapshot] = None,
    loading: Boolean = false,
    error: Option[String] = None
)

/** One generation owns bootstrap, bounded early notifications and live deltas. Recovery only repeats reads. */
private[web] final class ConversationGraphModel(
    subscribe: (EventsUpdate.Params => Unit) => EventSubscription,
    connectionVersion: () => Long,
    connected: () => Boolean = () => true,
    visible: () => Boolean = () => true,
    updateSummaries: List[SessionSummary] => Unit = _ => ()
)(using ExecutionContext) {
  import ConversationGraphModel.*
  val state = Var(GraphLoadState())
  private var enabled = false
  private var live = true
  private var epoch = 0L
  private var subscription = Option.empty[EventSubscription]
  private var confirmed = Map.empty[MessageId, MessageSnapshot]
  private var pending = Map.empty[String, List[MessageSnapshot]]
  private var sessions = Map.empty[String, PublishedSession]
  private def eligible: Boolean = enabled && live && connected() && visible()

  def isConfirmed(id: MessageId): Boolean = confirmed.contains(id)
  def enable(): Unit = enabled = true
  def disable(): Unit = { enabled = false; stop() }
  private def stop(): Unit = {
    epoch += 1
    subscription.foreach(_.cancel())
    subscription = None
    state.update(_.copy(loading = false))
  }
  def setLive(value: Boolean): Unit = if live != value then {
    live = value
    eligibilityChanged()
  }
  def eligibilityChanged(): Unit = if eligible then {
    if subscription.isEmpty then refresh()
  } else stop()
  def refresh(): Unit = if eligible then start(recoveries = 1)

  private def publish(): Unit = {
    val values = sessions.values.toList.sortBy(_.id.value)
    state.set(
      GraphLoadState(
        Some(
          GraphSnapshot(
            (confirmed.values.toList ++ pending.valuesIterator.flatten)
              .distinctBy(_.id)
              .sortBy(m => (m.timestamp.toString, MessageId.value(m.id))),
            values.map(s => GraphSessionNode(s.id.value, s.head.map(MessageId.value), s.error))
          )
        )
      )
    )
    updateSummaries(values.map(s => SessionSummary(s.id, s.messageCount, s.agentIds)))
  }

  private def start(recoveries: Int): Unit = {
    stop()
    val current = epoch
    val connection = connectionVersion()
    def valid: Boolean = eligible && current == epoch && connection == connectionVersion()
    var baseline = Option.empty[EventsSubscribe.Result]
    var early = Vector.empty[EventsUpdate.Params]
    var earlyBytes = 0L
    var sequence = 0L
    def invalid(reason: String): Unit = if valid then {
      stop()
      // Optimistic observations are not confirmed inventory. Remove ghosts even if recovery fails.
      pending = Map.empty
      if state.now().snapshot.nonEmpty then publish()
      state.update(_.copy(error = Some(reason)))
      if recoveries > 0 && eligible then start(recoveries - 1)
    }
    def applyEvent(event: EventsUpdate.Params): Unit = if valid then
      baseline.foreach { initial =>
        if event.subscriptionId != initial.subscriptionId || event.streamId != initial.streamId || event.nonce != initial.nonce
        then invalid("Graph stream changed. Refresh to recover.")
        else if event.sequence > sequence then {
          if event.sequence != sequence + 1 then invalid("Graph event gap. Refresh to recover.")
          else {
            sequence = event.sequence
            event.change match {
              case EventChange.Pending(id, messages) =>
                val observations = messages.toList.filterNot(m => confirmed.contains(m.id))
                if observations.nonEmpty then pending += id -> observations else pending -= id
                if pending.size > MaxEarlyEvents || pending.iterator.map { case (operationId, artifacts) =>
                    EventPending(operationId, Chunk.fromIterable(artifacts)).toJson.getBytes(UTF_8).length.toLong
                  }.sum > MaxEarlyBytes
                then invalid("Graph pending buffer overflow. Refresh to recover.")
                else publish()
              case EventChange.Confirmed(id, messages, changes) =>
                pending -= id
                confirmed ++= messages.map(m => m.id -> m)
                changes.foreach { value =>
                  if sessions.get(value.id.value).forall(_.revision < value.revision) then
                    sessions += value.id.value -> value
                }
                publish()
              case EventChange.Uncertain(_) => invalid("Graph outcome uncertain. Refresh to recover.")
            }
          }
        }
      }
    state.update(_.copy(loading = true, error = None))
    val handle = subscribe { event =>
      if valid then
        baseline match {
          case Some(_) => applyEvent(event)
          case None    =>
            earlyBytes += event.toJson.getBytes(UTF_8).length.toLong
            if early.size >= MaxEarlyEvents || earlyBytes > MaxEarlyBytes then
              invalid("Graph bootstrap buffer overflow. Refresh to recover.")
            else early :+= event
        }
    }
    if valid then subscription = Some(handle) else handle.cancel()
    handle.result.onComplete {
      case Success(initial)
          if valid && (initial.subscriptionId.isEmpty || initial.streamId.isEmpty || initial.startSequence < 0 || initial.pending.size > MaxEarlyEvents) =>
        invalid("Invalid graph baseline. Refresh to recover.")
      case Success(initial) if valid =>
        baseline = Some(initial)
        sequence = initial.startSequence
        // Retain confirmed disconnected branches; only the optimistic overlay is replaceable.
        confirmed ++= initial.messages.map(m => m.id -> m)
        pending =
          initial.pending.map(p => p.operationId -> p.messages.toList.filterNot(m => confirmed.contains(m.id))).toMap
        sessions = initial.sessions.map(s => s.id.value -> s).toMap
        publish()
        early.foreach(applyEvent)
        early = Vector.empty
      case Failure(error) if valid =>
        stop()
        state.update(_.copy(error = Some(Option(error.getMessage).getOrElse("Graph subscription failed"))))
      case _ => ()
    }
  }
}

private[web] object ConversationGraphModel {
  val MaxEarlyEvents = 128
  val MaxEarlyBytes = 2L * 1024 * 1024
}
