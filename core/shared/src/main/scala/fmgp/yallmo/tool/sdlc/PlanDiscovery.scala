package fmgp.yallmo.tool.sdlc

import zio.json.JsonCodec

/** Compact discovery result. Read the full task/intent before editing; a title is not replacement content. */
final case class PlanSummary(
    id: String,
    title: String,
    expectedRevision: Long,
    state: Option[TaskState],
    archived: Boolean
) derives JsonCodec

/** Continue with nextAfterId using the same search/filter; absent means there are no more current matches. */
final case class PlanDiscovery(items: List[PlanSummary], nextAfterId: Option[String]) derives JsonCodec
