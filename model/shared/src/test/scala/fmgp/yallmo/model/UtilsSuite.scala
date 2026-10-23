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

  test("sha256 agrees across platforms at padding and block boundaries") {
    val vectors = List(
      0 -> "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      55 -> "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318",
      56 -> "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a",
      64 -> "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb",
      65 -> "635361c48bb9eab14198e76ea8ab7f1a41685d6ad62aa9146d301d4f17eb0ae0",
      1000 -> "41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3"
    )
    for (length, expected) <- vectors do {
      val bytes = Array.fill[Byte](length)('a'.toByte)
      assertEquals(Utils.sha256(bytes), expected, clues(length))
      assertEquals(Utils.sha256("a" * length), expected, clues(length))
      assertEquals(Utils.sha256Bytes(bytes).length, 32)
    }
  }

  test("sha256 preserves UTF-8 and binary input") {
    assertEquals(Utils.sha256("héllo 🌍"), "cbbcee01a3fc5f1c0db23e02be25316adf28ede876031fdbabe5f4fabe47ed7f")
    assertEquals(
      Utils.sha256(Array.tabulate[Byte](256)(_.toByte)),
      "40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880"
    )
  }
