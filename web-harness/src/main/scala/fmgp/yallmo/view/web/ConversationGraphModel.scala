package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.Var
import fmgp.yallmo.model.MessageId
import fmgp.yallmo.orchestration.SessionSummary
import fmgp.yallmo.server.protocol.YallmoProtocol.{MessageSnapshot, SessionSnapshot}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

private[web] final case class GraphSession(id: String, head: Option[String], error: Option[String] = None)
private[web] final case class GraphSnapshot(messages: List[MessageSnapshot], sessions: List[GraphSession])
private[web] final case class GraphLoadState(
    snapshot: Option[GraphSnapshot] = None,
    loading: Boolean = false,
    error: Option[String] = None
)

/** Polling snapshots. SessionSummary has no head: read sessionsGet in batches of four and use the last message of its
  * oldest-first history. A failed session read is unknown, never interpreted as an empty published history. Epoch and
  * connection guards prevent old replies from applying after refresh/reconnect/unmount.
  */
private[web] final class ConversationGraphModel(
    messages: () => Future[List[MessageSnapshot]],
    sessions: () => Future[List[SessionSummary]],
    session: String => Future[SessionSnapshot],
    connectionVersion: () => Long,
    connected: () => Boolean = () => true,
    visible: () => Boolean = () => true,
    updateSummaries: List[SessionSummary] => Unit = _ => ()
)(using ExecutionContext) {
  val state = Var(GraphLoadState())
  private var enabled = false
  private var live = true
  private var epoch = 0L
  private var sessionReads = Future.successful(())

  def enable(): Unit = enabled = true
  def disable(): Unit = {
    enabled = false
    epoch += 1
    state.update(_.copy(loading = false))
  }

  def setLive(value: Boolean): Unit = if live != value then {
    live = value
    if value then refresh()
    else {
      epoch += 1
      state.update(_.copy(loading = false))
    }
  }

  // Busy ticks are discarded, not queued: slow snapshots must be allowed to finish without epoch churn.
  def poll(): Unit = if enabled && live && connected() && visible() && !state.now().loading then refresh()

  def refresh(): Unit = if enabled && live && connected() && visible() then {
    epoch += 1
    val current = epoch
    val connection = connectionVersion()
    def valid: Boolean =
      enabled && live && connected() && visible() && epoch == current && connectionVersion() == connection
    state.update(_.copy(loading = true))
    val request = for {
      inventory <- messages()
      summaries <- if valid then sessions() else Future.failed(new RuntimeException("Graph snapshot interrupted"))
      snapshots <- queueSessionReads(summaries, () => valid)
    } yield {
      val published = snapshots.flatMap(_._2.toOption.toList.flatMap(_.messages.toList))
      val snapshot = GraphSnapshot(
        (inventory ++ published).distinctBy(_.id),
        snapshots.map { case (id, result) =>
          result match {
            case Right(value) => GraphSession(id, value.messages.lastOption.map(message => MessageId.value(message.id)))
            case Left(error)  => GraphSession(id, None, Some(error))
          }
        }
      )
      (snapshot, summaries)
    }
    request.onComplete { result =>
      if enabled && current == epoch then {
        if !valid then
          state.update(
            _.copy(loading = false, error = Some("Snapshot interrupted. Updates resume when connected and visible."))
          )
        else
          result match {
            case Success((snapshot, summaries)) =>
              state.set(GraphLoadState(Some(snapshot)))
              updateSummaries(summaries)
            case Failure(error) =>
              state.update(
                _.copy(loading = false, error = Some(Option(error.getMessage).getOrElse("Graph refresh failed")))
              )
          }
      }
    }
  }

  // Serialize snapshot batches too: repeated refreshes cannot multiply the four in-flight session reads.
  private def queueSessionReads(
      summaries: List[SessionSummary],
      valid: () => Boolean
  ): Future[List[(String, Either[String, SessionSnapshot])]] = {
    val result = sessionReads.flatMap(_ => readSessions(summaries, valid))
    sessionReads = result.map(_ => ()).recover { case NonFatal(_) => () }
    result
  }

  private def readSessions(
      summaries: List[SessionSummary],
      valid: () => Boolean
  ): Future[List[(String, Either[String, SessionSnapshot])]] =
    summaries.grouped(4).foldLeft(Future.successful(List.empty[(String, Either[String, SessionSnapshot])])) {
      (previous, batch) =>
        previous.flatMap { accumulated =>
          if !valid() then Future.failed(new RuntimeException("Graph snapshot interrupted"))
          else
            Future
              .traverse(batch) { summary =>
                val id = summary.id.value
                session(id)
                  .map(value => id -> Right(value))
                  .recover { case NonFatal(error) =>
                    id -> Left(Option(error.getMessage).getOrElse("Session read failed"))
                  }
              }
              .map(accumulated ++ _)
        }
    }
}
