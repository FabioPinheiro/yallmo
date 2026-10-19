package fmgp.yallmo.orchestration.config

import fmgp.yallmo.model.{AgentId, ToolChoice, YallmoError}
import fmgp.yallmo.model.llm.AgentTurnRequest
import fmgp.yallmo.orchestration.StaticContext
import zio.json.JsonCodec

/** None inherits the supplied catalog; Some(empty) disables that capability, even in a partial object. */
final case class AgentCapabilities(
    tools: Option[Set[String]] = None,
    retrievers: Option[Set[String]] = None,
    skills: Option[Set[String]] = None,
    extensions: Option[Set[String]] = None
) derives JsonCodec {
  def allowsTool(key: String): Boolean = tools.forall(_.contains(key))

  /** Resolve against the application's available context, rejecting typos before removing capabilities. */
  def resolve[R](id: AgentId, available: StaticContext[R]): Either[YallmoError, StaticContext[R]] = {
    def check(kind: String, allowed: Option[Set[String]], known: Set[String]): Either[YallmoError, Unit] =
      val unknown = allowed.getOrElse(Set.empty) -- known
      Either.cond(
        unknown.isEmpty,
        (),
        YallmoError.InvalidConfig(
          s"Agent '${AgentId.value(id)}' capabilities.$kind contains unknown IDs: ${unknown.toList.sorted.mkString(", ")}; check the available $kind catalog"
        )
      )
    for {
      _ <- check("tools", tools, available.tools.map(_.key).toSet)
      _ <- check("retrievers", retrievers, available.retrievers.map(_.key).toSet)
      _ <- check("skills", skills, available.skills.map(_.key).toSet)
      _ <- check("extensions", extensions, available.extensions.map(_.key).toSet)
    } yield available.copy(
      tools = available.tools.filter(t => allowsTool(t.key)),
      retrievers = available.retrievers.filter(t => retrievers.forall(_.contains(t.key))),
      skills = available.skills.filter(t => skills.forall(_.contains(t.key))),
      extensions = available.extensions.filter(t => extensions.forall(_.contains(t.key)))
    )
  }

  /** Request-level filtering also covers callers that bypass native program/context assembly. */
  def filterRequest(request: AgentTurnRequest): AgentTurnRequest =
    if tools.isEmpty then request
    else {
      val permitted = request.tools.filter(t => allowsTool(t.name))
      request.copy(
        tools = permitted,
        toolChoice = request.toolChoice match {
          case ToolChoice.Function(name) if !permitted.exists(_.name == name) => ToolChoice.None
          case _ if permitted.isEmpty                                         => ToolChoice.None
          case choice                                                         => choice
        },
        parallelToolCalls = request.parallelToolCalls && permitted.nonEmpty
      )
    }

  /** Binding cannot widen either the program's or the runtime adapter's restrictions. */
  def intersect(other: AgentCapabilities): AgentCapabilities = {
    def both(a: Option[Set[String]], b: Option[Set[String]]): Option[Set[String]] = (a, b) match {
      case (Some(x), Some(y)) => Some(x intersect y)
      case (Some(x), None)    => Some(x)
      case (None, y)          => y
    }
    AgentCapabilities(
      both(tools, other.tools),
      both(retrievers, other.retrievers),
      both(skills, other.skills),
      both(extensions, other.extensions)
    )
  }
}

/** Local marker retained by configured adapters so bound native programs inherit the configured policy. */
trait HasAgentCapabilities {
  def capabilities: AgentCapabilities
}
