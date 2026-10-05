package fmgp.yallmo.model

import munit.FunSuite
import zio.json.{DecoderOps, EncoderOps}

import java.time.Instant

final class UtilsSuite extends FunSuite:
  test("sha256 produces the standard lowercase hexadecimal digest") {
    assertEquals(
      Utils.sha256("hello"),
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    )

    val data = MessageData(
      AgentId("agent"),
      None,
      Instant.parse("2026-01-01T00:00:00Z"),
      Message.User("hello")
    )
    assertEquals(Message(data).id, MessageId.sha256(data.toJson))
    assertEquals(data.toJson.fromJson[MessageData], Right(data))
  }
