package fmgp.yallmo.view

import zio.json.JsonCodec

/** Version-one read API. Plain values only: no runtime agents, credentials or provider continuation state. This source
  * is compiled by both server (JVM) and view-web (Scala.js).
  */
object ViewProtocol {
  final case class SessionSummaryView(id: String, messageCount: Long, agentIds: List[String]) derives JsonCodec

  final case class SessionView(id: String, agentIds: List[String], messages: List[MessageView]) derives JsonCodec

  final case class MessageView(
      id: String,
      agentId: String,
      parentId: Option[String],
      timestamp: String,
      role: String,
      content: String,
      blobIds: List[String],
      toolName: Option[String] = None
  ) derives JsonCodec

  final case class ApiError(code: String, message: String) derives JsonCodec
}
