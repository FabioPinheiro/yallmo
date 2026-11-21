package fmgp.yallmo.orchestration

import fmgp.yallmo.model.{AgentId, MessageId, MessagePayload}
import fmgp.yallmo.model.llm.AgentTurnRequest
import java.time.Instant
import munit.FunSuite
import zio.json.*

final class SessionSuite extends FunSuite {
  test("legacy references decode without invented metadata or info") {
    assertEquals(
      """{"agentIds":["author"]}""".fromJson[SessionRef],
      Right(SessionRef(Seq(AgentId("author")), None))
    )
    assertEquals("{}".fromJson[SessionMetadata], Right(SessionMetadata()))
    assertEquals("{}".fromJson[SessionInfo], Right(SessionInfo()))
  }

  test("session references round-trip metadata, info, timestamps and parent IDs") {
    val now = Instant.parse("2026-01-02T03:04:05.123456789Z")
    val reference = SessionRef(
      Seq(AgentId("author")),
      Some(MessageId.sha256("head")),
      Some(SessionMetadata(Some(now), Some(now), Some(now), 7L)),
      Some(SessionInfo(Some("Title"), Some(SessionId.unsafe("parent")), Some("Goals"), Some("Status")))
    )
    assertEquals(reference.toJsonPretty.fromJson[SessionRef], Right(reference))
    assertEquals(reference.toJson.fromJson[SessionRef], Right(reference))
  }

  test("payload snapshots and provider requests accept lists and vectors without conversion") {
    val first = MessagePayload.User("First")
    val second = MessagePayload.Assistant("Second")
    val last = MessagePayload.Instructions("System")
    val context = RunContext(List(first)).append(second).appendAll(Vector(last))
    assertEquals(context.messages, List(first, second, last))
    val request = AgentTurnRequest("test-model", context.messages)
    assertEquals(request.validate, Right(request))
    assertEquals(RunContext().appendAll(List(first, second)).messages, Vector(first, second))
  }

  test("session info validates parent IDs when decoding persistence") {
    assert("""{"parentSessionId":"../outside"}""".fromJson[SessionInfo].isLeft)
    assert("""{"meta":{"createdAt":"not-an-instant"},"agentIds":[]}""".fromJson[SessionRef].isLeft)
  }
}
