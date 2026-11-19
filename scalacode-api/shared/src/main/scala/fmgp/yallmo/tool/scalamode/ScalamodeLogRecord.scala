package fmgp.yallmo.tool.scalamode

import zio.json.*

/** One runtime log format; platform runners own timestamps, bounded byte writes and file lifetime. */
private[scalamode] final case class ScalamodeLogRecord(
    timestamp: String,
    level: String,
    fiber: String,
    message: String,
    cause: Option[String],
    annotations: Map[String, String]
)
private[scalamode] object ScalamodeLogRecord {
  given JsonEncoder[ScalamodeLogRecord] = DeriveJsonEncoder.gen[ScalamodeLogRecord]
}
