package fmgp.yallmo.model

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import zio.ZIO
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
  * REVIEW: Keep the environment requirement on the trait: it describes the services an agent needs for every turn. A
  * method-level `turn[R]` would require every implementation to work for arbitrary `R`, preventing an agent from
  * requiring services such as an HTTP client or credentials. All current concrete agents use `Any`; if that remains
  * true, remove the environment parameter entirely rather than moving it to `turn`.
  */
trait Agent[-Environment] {
  def id: AgentId

  /** Do not call this method 'infer'. Infer is the correct verb for performing inference. But Agent might not be a LLM!
    *
    * We should also have a method for a stream type API.
    */
  def turn(request: AgentTurnRequest): ZIO[Environment, YallmoError, AgentTurnResponse]
}
