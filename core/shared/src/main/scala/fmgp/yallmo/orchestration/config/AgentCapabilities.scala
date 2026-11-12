package fmgp.yallmo.orchestration.config

import fmgp.yallmo.model.{AgentId, YallmoError}
import fmgp.yallmo.tool.{ToolDefinition, FunctionKey, ToolKey}

import fmgp.yallmo.model.llm.AgentTurnRequest
import fmgp.yallmo.orchestration.{StaticContext}
import zio.json.JsonCodec
import zio.Chunk

/** None inherits the supplied catalog; Some(empty) disables that capability, even in a partial object.
  *
  * Tool and function restrictions are conjunctive: a function allowlist can narrow, but never enable a disabled tool.
  * Old per-operation `tools` allowlists must move to `functions`; `tools` now names capability groups.
  */
final case class AgentCapabilities(
    tools: Option[Set[ToolKey]] = None,
    retrievers: Option[Set[String]] = None,
    skills: Option[Set[String]] = None,
    extensions: Option[Set[String]] = None,
    functions: Option[Set[FunctionKey]] = None
) derives JsonCodec {
  def allowsTool(key: ToolKey): Boolean = tools.forall(_.contains(key))

  def allowsFunction(functionKey: FunctionKey, catalog: Chunk[ToolDefinition]): Boolean =
    functions.forall(_.contains(functionKey)) && tools.forall { allowed =>
      catalog.exists(tool => allowed.contains(tool.name) && tool.functions.exists(_.name == functionKey))
    }

  /** Resolve against the application's available context, rejecting typos before removing capabilities. */
  def resolve[R](id: AgentId, available: StaticContext[R]): Either[YallmoError, StaticContext[R]] = {
    def check[A](kind: String, allowed: Option[Set[A]], known: Set[A]): Either[YallmoError, Unit] =
      val unknown = allowed.getOrElse(Set.empty) -- known
      Either.cond(
        unknown.isEmpty,
        (),
        YallmoError.InvalidConfig(
          s"Agent '${AgentId.value(id)}' capabilities.$kind contains unknown IDs: ${unknown.toList.map(_.toString).sorted.mkString(", ")}; check the available $kind catalog"
        )
      )
    val duplicateFunctions = available.functions.map(_.name).groupBy(identity).collect {
      case (key, occurrences) if occurrences.size > 1 => key.value
    }
    for {
      _ <- Either.cond(
        duplicateFunctions.isEmpty,
        (),
        YallmoError.InvalidConfig(
          s"Duplicate function keys in available catalog: ${duplicateFunctions.toList.sorted.mkString(", ")}"
        )
      )
      _ <- check("tools", tools, available.tools.map(_.name).toSet)
      _ <- check("functions", functions, available.functions.map(_.name).toSet)
      _ <- check("retrievers", retrievers, available.retrievers.map(_.key).toSet)
      _ <- check("skills", skills, available.skills.map(_.key).toSet)
      _ <- check("extensions", extensions, available.extensions.map(_.key).toSet)
    } yield restrict(available)
  }

  /** Reapply a validated policy to an already narrowed context without treating removed IDs as configuration typos. */
  def restrict[R](available: StaticContext[R]): StaticContext[R] = {
    val permitted = available.functions.filter(function => allowsFunction(function.name, available.tools))
    available.copy(
      functions = permitted,
      tools = ToolDefinition.available(available.tools.filter(tool => allowsTool(tool.name)), permitted),
      retrievers = available.retrievers.filter(t => retrievers.forall(_.contains(t.key))),
      skills = available.skills.filter(t => skills.forall(_.contains(t.key))),
      extensions = available.extensions.filter(t => extensions.forall(_.contains(t.key)))
    )
  }

  /** Request-level filtering also covers callers that bypass native program/context assembly. */
  def filterRequest(request: AgentTurnRequest): AgentTurnRequest =
    if tools.isEmpty && functions.isEmpty then request
    else {
      val permitted = request.tools.filter(f => allowsFunction(f.name, request.toolGroups))
      request.copy(
        tools = permitted,
        toolGroups = ToolDefinition
          .available(request.toolGroups.filter(tool => allowsTool(tool.name)), permitted),
        parallelToolCalls = request.parallelToolCalls && permitted.nonEmpty
      )
    }

  /** Binding cannot widen either the program's or the runtime adapter's restrictions. */
  def intersect(other: AgentCapabilities): AgentCapabilities = {
    def both[A](a: Option[Set[A]], b: Option[Set[A]]): Option[Set[A]] = (a, b) match {
      case (Some(x), Some(y)) => Some(x intersect y)
      case (Some(x), None)    => Some(x)
      case (None, y)          => y
    }
    AgentCapabilities(
      tools = both(tools, other.tools),
      retrievers = both(retrievers, other.retrievers),
      skills = both(skills, other.skills),
      extensions = both(extensions, other.extensions),
      functions = both(functions, other.functions)
    )
  }
}

/** Local marker retained by configured adapters so bound native programs inherit the configured policy. */
trait HasAgentCapabilities {
  def capabilities: AgentCapabilities
}
