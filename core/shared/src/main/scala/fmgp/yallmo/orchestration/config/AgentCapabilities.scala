package fmgp.yallmo.orchestration.config

import fmgp.yallmo.model.{AgentId, YallmoError}
import fmgp.yallmo.tool.{ToolDefinition, FunctionKey, ToolKey}

import fmgp.yallmo.model.llm.AgentTurnRequest
import fmgp.yallmo.orchestration.{StaticContext}
import zio.json.{JsonCodec, jsonNoExtraFields}
import zio.Chunk

/** None inherits the supplied catalog; Some(empty) disables that capability, even in a partial object.
  *
  * Tool and function restrictions are conjunctive: a function allowlist can narrow, but never enable a disabled tool.
  * Old per-operation `tools` allowlists must move to `functions`; `tools` now names capability groups.
  */
@jsonNoExtraFields
final case class AgentCapabilities(
    tools: Option[Set[ToolKey]] = None,
    skills: Option[Set[String]] = None,
    functions: Option[Set[FunctionKey]] = None
) derives JsonCodec {
  def allowsTool(key: ToolKey): Boolean = tools.forall(_.contains(key))

  def allowsFunction(functionKey: FunctionKey, catalog: Chunk[ToolDefinition]): Boolean =
    functions.forall(_.contains(functionKey)) && tools.forall { allowed =>
      catalog.exists(tool => allowed.contains(tool.name) && tool.functions.exists(_.name == functionKey))
    }

  /** Resolve against the application's available context, rejecting typos before removing capabilities. */
  def resolve[R](id: AgentId, available: StaticContext[R]): Either[YallmoError, StaticContext[R]] =
    validateIds(id, available).map(_ => restrict(available))

  /** Validate explicit IDs and catalog uniqueness without restricting the context or trusted wiring output. */
  def validateIds[R](id: AgentId, available: StaticContext[R]): Either[YallmoError, Unit] = {
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
      _ <- check("skills", skills, available.skills.map(_.key).toSet)
    } yield ()
  }

  /** Filter without ID validation, before trusted wiring or for already narrowed contexts. Validate explicit IDs with
    * validateIds against the complete available catalog, not an intermediate stage's execution catalog.
    */
  def restrict[R](available: StaticContext[R]): StaticContext[R] = {
    val permitted = available.functions.filter(function => allowsFunction(function.name, available.tools))
    available.copy(
      functions = permitted,
      tools = ToolDefinition.available(available.tools.filter(tool => allowsTool(tool.name)), permitted),
      skills = available.skills.filter(t => skills.forall(_.contains(t.key)))
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
      skills = both(skills, other.skills),
      functions = both(functions, other.functions)
    )
  }
}

/** Local marker retained by configured adapters so bound native programs inherit the configured policy. */
trait HasAgentCapabilities {
  def capabilities: AgentCapabilities
}
