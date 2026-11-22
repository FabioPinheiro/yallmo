package fmgp.yallmo.webharness

import fmgp.yallmo.orchestration.{SessionId, SessionInfo, SessionMetadata, SessionSummary}
import java.time.Instant
import munit.FunSuite

final class SessionPresentationSuite extends FunSuite {
  private val old = Instant.parse("2026-01-01T00:00:00Z")
  private val recent = old.plusSeconds(1)
  private def summary(id: String, meta: Option[SessionMetadata] = None): SessionSummary =
    SessionSummary(SessionId.unsafe(id), 0L, Nil, meta)

  test("updated date wins over creation date; creation is the fallback and unknown dates come last") {
    val updated = summary("z-updated", Some(SessionMetadata(createdAt = Some(old), updatedAt = Some(recent))))
    val created = summary("a-created", Some(SessionMetadata(createdAt = Some(old))))
    val unknown = summary("b-legacy")
    val empty = summary("a-empty", Some(SessionMetadata()))
    assertEquals(
      SessionPresentation.sorted(List(unknown, created, empty, updated)),
      List(updated, created, empty, unknown)
    )
  }

  test("equal dates and missing dates have a stable ascending ID tie-breaker") {
    val a = summary("a", Some(SessionMetadata(updatedAt = Some(recent))))
    val b = summary("b", a.meta)
    assertEquals(SessionPresentation.sorted(List(b, a)), List(a, b))
    assertEquals(SessionPresentation.sorted(List(summary("b"), summary("a"))), List(summary("a"), summary("b")))
  }

  test("sorting preserves sub-millisecond date precision") {
    val a = summary("a", Some(SessionMetadata(updatedAt = Some(recent))))
    val b = summary("b", Some(SessionMetadata(updatedAt = Some(recent.plusNanos(1)))))
    assertEquals(SessionPresentation.sorted(List(a, b)), List(b, a))
  }

  test("nonblank titles are trimmed and missing or blank titles fall back to the session ID") {
    assertEquals(
      SessionPresentation.displayName("session", Some(SessionInfo(title = Some("  Research  ")))),
      "Research"
    )
    for info <- List(None, Some(SessionInfo()), Some(SessionInfo(title = Some(" \n\t ")))) do
      assertEquals(SessionPresentation.displayName("session", info), "session")
  }
}
