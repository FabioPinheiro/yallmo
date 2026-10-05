package fmgp.yallmo.model.llm

import zio.json.JsonCodec

/** Opaque provider-owned continuation state associated with one point in a conversation.
  *
  * A provider may return this value so a later request can continue internal reasoning, tool-use state, or another
  * server-defined process. Yallmo does not decrypt, inspect, create, alter, summarize, or use its contents for
  * application decisions.
  *
  * Invariants:
  *   - Replay it unchanged and in transcript order.
  *   - Send it only through the adapter identified by [[provider]].
  *   - Do not convert it to a user, system, assistant, or tool message.
  *   - Do not expose it in normal history or logs; debug capture must redact it by default.
  *   - Drop it when switching providers or replacing its transcript segment during compaction.
  */
final case class ProviderContinuation(
    provider: ProviderId,
    itemId: Option[String],
    encryptedContent: String
) derives JsonCodec {
  def validate: Either[String, ProviderContinuation] =
    Either.cond(encryptedContent.nonEmpty, this, "provider continuation encrypted content must not be empty")
}
