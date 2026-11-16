package fmgp.yallmo

import java.nio.file.{Files, Path}
import munit.ZSuite
import zio.*

final class LlmProviderPayloadDebugSuite extends ZSuite:
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

  testZ("refuses accidental inline audio capture without mutating the live HTTP payload")(ZIO.suspendSucceed {
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
    LlmProviderPayloadDebug
      .withRequestCapture(LlmProviderPayloadDebug.captureRequest(turnId, "test", "model", live))
      .map { case (_, captured) =>
        assertEquals(captured, None)
        assert(!Files.exists(Path.of("debug", s"$turnId-request.json")))
        assertEquals(live("messages")(0)("content")(0)("input_audio")("data").str, "recording-sentinel")
      }
  })

  testZ("request capture scopes retain only requests made within that scope")(ZIO.suspendSucceed {
    val turnId = LlmProviderPayloadDebug.newTurnId()
    val path = Path.of("debug", s"$turnId-request.json")
    ZIO.scoped {
      for {
        _ <- ZIO.addFinalizer(ZIO.attempt(Files.deleteIfExists(path)).orDie)
        (_, captured) <- LlmProviderPayloadDebug.withRequestCapture(
          LlmProviderPayloadDebug.captureRequest(turnId, "test", "test-model", ujson.Obj()).as("done")
        )
        (_, empty) <- LlmProviderPayloadDebug.withRequestCapture(ZIO.succeed("done"))
      } yield {
        assertEquals(captured, Some(path))
        assertEquals(empty, None)
      }
    }
  })

  testZ("sensitive workflow suppresses request and response capture and restores capture afterwards")(
    ZIO.suspendSucceed {
      val privateId = LlmProviderPayloadDebug.newTurnId()
      val publicId = LlmProviderPayloadDebug.newTurnId()
      val publicPath = Path.of("debug", s"$publicId-request.json")
      val response = fmgp.yallmo.model.llm.AgentTurnResponse(
        Chunk(fmgp.yallmo.model.MessagePayload.Assistant("private")),
        fmgp.yallmo.model.llm.LlmStopReason.Stop
      )
      ZIO.scoped {
        for {
          _ <- ZIO.addFinalizer(ZIO.attempt(Files.deleteIfExists(publicPath)).orDie)
          (_, captured) <- LlmProviderPayloadDebug.withRequestCapture(
            LlmProviderPayloadDebug
              .withoutCapture(
                LlmProviderPayloadDebug
                  .captureRequest(privateId, "test", "model", ujson.Obj("private" -> "evidence")) *>
                  LlmProviderPayloadDebug.captureResponse(privateId, "test", "model", response)
              )
              .fork
              .flatMap(_.join)
          )
          _ <- ZIO.succeed {
            assertEquals(captured, None)
            assert(!Files.exists(Path.of("debug", s"$privateId-request.json")))
            assert(!Files.exists(Path.of("debug", s"$privateId-response.json")))
          }
          _ <- LlmProviderPayloadDebug.captureRequest(publicId, "test", "model", ujson.Obj())
        } yield assert(Files.exists(publicPath))
      }
    }
  )
