package fmgp.yallmo.model

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.AgentTool
import zio.{IO, ZIO}
import zio.json.{JsonCodec, JsonFieldDecoder, JsonFieldEncoder}

opaque type AgentId = String

object AgentId {
  def from(value: String): Either[String, AgentId] =
    Either.cond(value.trim.nonEmpty, value, "agent id must not be blank")

  // TODO rename to apply method!
  def apply(value: String): AgentId = value

  /** Creates a deterministic agent ID from the SHA-256 of UTF-8 text. */
  def sha256(value: String): AgentId = Utils.sha256(value)

  def value(id: AgentId): String = id

  given JsonCodec[AgentId] = JsonCodec.string.transformOrFail(AgentId.from, AgentId.value)
  given JsonFieldEncoder[AgentId] = JsonFieldEncoder.string.contramap(AgentId.value)
  given JsonFieldDecoder[AgentId] = JsonFieldDecoder.string.mapOrFail(AgentId.from)
}

/** Provider-neutral agent capable of executing one requested turn.
  *
  * Its ID is the stable identity used to register the agent and attribute persisted messages.
  *
  * The environment describes the services needed for each turn, such as a rooted filesystem capability. Callers
  * initialize and supply those services; each agent decides how to use them to complete its turn.
  */
trait Agent[-Environment] {
  def id: AgentId

  /** Transform the permitted tools once at resolution time. The default preserves the supplied catalog. Overrides
    * compose utility factories explicitly. Permission filtering happens only before this method; resolution validates
    * the returned catalog without filtering it again. Wiring owns any added capabilities.
    */
  def toolWirings(tools: Seq[AgentTool[Any]]): IO[YallmoError, Seq[AgentTool[Any]]] = ZIO.succeed(tools)

  /** Do not call this method 'infer'. Infer is the correct verb for performing inference. But Agent might not be a LLM!
    *
    * We should also have a method for a stream type API.
    */
  def turn(request: AgentTurnRequest): ZIO[Environment, YallmoError, AgentTurnResponse]
}

/** Explicit audio capability using the same turn contract; endpoint/model support is checked by the provider. */
trait AudioAgent[-Environment] extends Agent[Environment]
