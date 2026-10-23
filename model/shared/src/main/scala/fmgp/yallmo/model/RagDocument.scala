package fmgp.yallmo.model

import zio.json.JsonCodec

/** A source-attributed document supplied as retrieval context. */
final case class RagDocument(
    id: String,
    source: String,
    text: String,
    score: Option[Double] = None
) derives JsonCodec
