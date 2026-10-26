package fmgp.yallmo.view.web

import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import fmgp.yallmo.server.protocol.YallmoProtocol.given
import zio.Chunk
import munit.FunSuite
import zio.json.*

final class ViewWebSuite extends FunSuite {
  test("session hashes round-trip and malformed hashes do not throw") {
    List("main", "session-one", "session.with.dots", "space name").foreach { id =>
      assertEquals(SessionRoute.parse(SessionRoute.href(id)), Some(id))
    }
    List("", "#/", "#/sessions/", "#/other/main", "#/sessions/%ZZ").foreach { hash =>
      assertEquals(SessionRoute.parse(hash), None)
    }
  }

  test("all tabs and selected sessions round-trip through hash navigation") {
    (ViewRoute.tabs :+ ViewRoute.Sessions(Some("my-session"))).foreach { page =>
      assertEquals(ViewRoute.parse(page.href), page)
    }
    assertEquals(ViewRoute.parse("#/chat"), ViewRoute.Chat)
    assertEquals(ViewRoute.Chat.href, "#/chat")
    assertEquals(ViewRoute.parse("#/unknown"), ViewRoute.Sessions(None))
    assertEquals(ViewRoute.parse("#/sessions/%ZZ"), ViewRoute.Sessions(None))
  }

  test("only function calls and outputs pretty-print valid JSON, without changing invalid JSON or text") {
    val raw = """{"result":[1,2],"ok":true}"""
    val pretty = MessageText.format("tool_call", raw)
    assert(pretty.contains("\n"))
    assertEquals(pretty.fromJson[zio.json.ast.Json], raw.fromJson[zio.json.ast.Json])
    assertEquals(MessageText.format("tool_result", raw), pretty)
    assertEquals(MessageText.format("tool_result", "not JSON\n<untrusted>"), "not JSON\n<untrusted>")
    assertEquals(MessageText.format("assistant", raw), raw)
    assertEquals(MessageText.format("user", raw), raw)
  }

  test("long content collapses by character count or line count") {
    assert(!MessageText.isLong("short text"))
    assert(!MessageText.isLong("x" * 1200))
    assert(MessageText.isLong("x" * 1201))
    assert(!MessageText.isLong(List.fill(16)("line").mkString("\n")))
    assert(MessageText.isLong(List.fill(17)("line").mkString("\n")))
    assert(MessageText.isLong(MessageText.format("tool_result", "[" + List.fill(20)("1").mkString(",") + "]")))
  }

  test("the shared JVM/JS contract decodes transcript text and attachment references") {
    val json =
      """{"id":"main","agentIds":["human"],"messages":[{"id":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","agentId":"human","timestamp":"2026-01-01T00:00:00Z","role":"user","content":"<script>not HTML</script>","blobIds":["00000000-0000-0000-0000-000000000001"]}]}"""
    val result = json.fromJson[SessionSnapshot]
    assertEquals(result.map(_.messages.head.content), Right("<script>not HTML</script>"))
    assertEquals(result.map(_.messages.head.parentId), Right(None))
    assertEquals(result.flatMap(value => value.toJson.fromJson[SessionSnapshot]), result)
  }

  test("API failures and empty session lists decode without browser dependencies") {
    assertEquals(
      """{"jsonrpc":"2.0","id":"r1","error":{"code":-32004,"message":"Not found"}}"""
        .fromJson[JsonRpc.Failure]
        .map(_.error),
      Right(JsonRpc.Error(ErrorCode.NotFound, "Not found"))
    )
    assertEquals("[]".fromJson[SessionsList.Result], Right(Chunk.empty))
    assert("not JSON".fromJson[SessionSnapshot].isLeft)
  }
}
