package fmgp.yallmo.webharness

import fmgp.yallmo.orchestration.{SessionInfo, SessionSummary}

/** Shared presentation rules; audit dates do not represent message or execution activity. */
private[webharness] object SessionPresentation {
  def displayName(id: String, info: Option[SessionInfo]): String =
    info.flatMap(_.title).map(_.trim).filter(_.nonEmpty).getOrElse(id)

  /** Most recently updated first, then creation date; unknown dates last, with IDs breaking ties consistently. */
  def sorted(sessions: List[SessionSummary]): List[SessionSummary] =
    sessions.sortWith { (left, right) =>
      val leftDate = left.meta.flatMap(m => m.updatedAt.orElse(m.createdAt))
      val rightDate = right.meta.flatMap(m => m.updatedAt.orElse(m.createdAt))
      (leftDate, rightDate) match {
        case (Some(a), Some(b)) if a != b => a.isAfter(b)
        case (Some(_), None)              => true
        case (None, Some(_))              => false
        case _                            => left.id.value < right.id.value
      }
    }
}
