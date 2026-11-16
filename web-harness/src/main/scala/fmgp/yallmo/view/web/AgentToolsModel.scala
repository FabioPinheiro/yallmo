package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import fmgp.yallmo.server.protocol.YallmoProtocol.ToolsList
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

private[web] final case class AgentToolsState(
    loading: Boolean = false,
    tools: Option[ToolsList.Result] = None,
    error: Option[String] = None
)

/** Explicit, read-only inspection. Unmount/reconnect invalidates replies; failed reads are never retried implicitly. */
private[web] final class AgentToolsModel(fetch: () => Future[ToolsList.Result]) {
  val state = Var(AgentToolsState())
  private var revision = 0L

  def reload(): Unit = if !state.now().loading then {
    revision += 1
    val request = revision
    state.set(AgentToolsState(loading = true))
    Try(fetch()).fold(Future.failed, identity).onComplete {
      case Success(tools) if request == revision => state.set(AgentToolsState(tools = Some(tools)))
      case Failure(error) if request == revision =>
        state.set(AgentToolsState(error = Some(Option(error.getMessage).getOrElse("Tool inspection failed"))))
      case _ => ()
    }
  }

  def reset(): Unit = {
    revision += 1
    state.set(AgentToolsState())
  }
}
