package fmgp.yallmo.webharness

import com.raquo.laminar.api.L.*
import fmgp.yallmo.server.protocol.YallmoProtocol.{AgentsSkills, ToolsList}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

/** Skills are inspected on demand, independently of tools. Collapsing discards the result. */
private[webharness] final class AgentSkillsModel(fetch: () => Future[AgentsSkills.Result]) {
  private val inspection = new InspectionModel[AgentsSkills.Result]
  val state = inspection.state
  val expanded = Var(false)

  def toggle(): Unit = if !state.now().loading then {
    if state.now().value.nonEmpty then reset()
    else {
      expanded.set(true)
      inspection.load(fetch())
    }
  }

  def reset(): Unit = {
    inspection.invalidate()
    expanded.set(false)
  }
}

private[webharness] final case class AgentToolsState(
    loading: Boolean = false,
    tools: Option[ToolsList.Result] = None,
    error: Option[String] = None
)

/** Explicit, read-only inspection. Unmount/reconnect invalidates replies; failed reads are never retried implicitly. */
private[webharness] final class AgentToolsModel(fetch: () => Future[ToolsList.Result]) {
  val state = Var(AgentToolsState())
  val expanded = Var(false)
  private var revision = 0L

  /** Inspect on demand; collapsing discards the result. Errors allow explicit retry. */
  def toggle(): Unit = if !state.now().loading then {
    if state.now().tools.nonEmpty then reset()
    else {
      expanded.set(true)
      inspect()
    }
  }

  private def inspect(): Unit = {
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
    expanded.set(false)
  }
}
