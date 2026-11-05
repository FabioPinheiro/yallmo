package fmgp.yallmo.model

import zio.json.{JsonCodec, JsonFieldDecoder, JsonFieldEncoder}

/** Connection owner, distinct from the AgentId recorded as a message's author. Not an authentication credential. */
opaque type UserId = String

object UserId {
  def from(value: String): Either[String, UserId] =
    Either.cond(value.trim.nonEmpty, value, "user id must not be blank")

  def apply(value: String): UserId = from(value).fold(message => throw new IllegalArgumentException(message), identity)
  def value(id: UserId): String = id

  /** Default actors provisioned by the trusted-local server policy. */
  def chatAgent(id: UserId): AgentId = AgentId("user-" + value(id))
  def websiteAgent(id: UserId): AgentId = AgentId("web-user-" + value(id))

  given JsonCodec[UserId] = JsonCodec.string.transformOrFail(from, value)
  given JsonFieldEncoder[UserId] = JsonFieldEncoder.string.contramap(value)
  given JsonFieldDecoder[UserId] = JsonFieldDecoder.string.mapOrFail(from)
}
