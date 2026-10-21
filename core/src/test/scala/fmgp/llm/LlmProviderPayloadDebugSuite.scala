package fmgp.yallmo

import java.nio.file.{Files, Path}
import munit.FunSuite
import zio.*

final class LlmProviderPayloadDebugSuite extends FunSuite:
  test("redacts encrypted reasoning recursively without changing other payload fields") {
    val redacted = LlmProviderPayloadDebug.redact(
      ujson.read(
        """{"instructions":"keep","input":[{"encrypted_content":"secret"},{"nested":{"encryptedContent":"also-secret"}}]}"""
      )
    )

    assertEquals(
      ujson.write(redacted),
      """{"instructions":"keep","input":[{"encrypted_content":"<redacted>"},{"nested":{"encryptedContent":"<redacted>"}}]}"""
    )
  }

  test("refuses accidental inline audio capture without mutating the live HTTP payload") {
    val live = ujson.Obj(
      "messages" -> ujson.Arr(
        ujson.Obj(
          "content" -> ujson.Arr(
            ujson.Obj(
              "type" -> "input_audio",
              "input_audio" -> ujson.Obj("data" -> "recording-sentinel", "format" -> "wav")
            ),
            ujson.Obj("type" -> "text", "text" -> "keep")
          )
        )
      )
    )
    val turnId = LlmProviderPayloadDebug.newTurnId()
    val (_, captured) = runZIO(
      LlmProviderPayloadDebug.withRequestCapture(LlmProviderPayloadDebug.captureRequest(turnId, "test", "model", live))
    )
    assertEquals(captured, None)
    assert(!Files.exists(Path.of("debug", s"$turnId-request.json")))
    assertEquals(live("messages")(0)("content")(0)("input_audio")("data").str, "recording-sentinel")
  }

  test("request capture scopes retain only requests made within that scope") {
    val turnId = LlmProviderPayloadDebug.newTurnId()
    val path = Path.of("debug", s"$turnId-request.json")
    try {
      val (_, captured) = runZIO(
        LlmProviderPayloadDebug.withRequestCapture(
          LlmProviderPayloadDebug.captureRequest(turnId, "test", "test-model", ujson.Obj()).as("done")
        )
      )
      val (_, empty) = runZIO(LlmProviderPayloadDebug.withRequestCapture(ZIO.succeed("done")))

      assertEquals(captured, Some(path))
      assertEquals(empty, None)
    } finally Files.deleteIfExists(path)
  }

  private def runZIO[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
