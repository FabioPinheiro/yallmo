package fmgp.yallmo.model

import munit.FunSuite
import zio.json.{DecoderOps, EncoderOps}

import java.time.Instant

final class MessageSuite extends FunSuite {
  private val timestamp = Instant.parse("2026-01-01T00:00:00Z")
  private val data = MessageData(AgentId("human"), None, timestamp, Message.User("héllo 🌍"))
  private val message = Message(data)
  private val dataJson =
    """{"agentId":"human","timestamp":"2026-01-01T00:00:00Z","payload":{"User":{"text":"héllo 🌍"}}}"""
  private val expectedId = "sha256:cbcecc2b605fad64ba60f50e4b0e0bf1953d32543978e720a8cdbd236b4c0b44"
  private val messageJson = s"""{"id":"$expectedId","data":$dataJson}"""

  test("message preserves the id/data wire shape and UTF-8 content address across platforms") {
    assertEquals(data.toJson, dataJson)
    assertEquals(MessageId.value(message.id), expectedId)
    assertEquals(message.id, MessageId.from(data))
    assertEquals(message.toJson, messageJson)
    assertEquals(messageJson.fromJson[Message], Right(message))
  }

  private val audio = MessagePayload.AudioAttachment(
    BlobId.from("00000000-0000-0000-0000-000000000001").toOption.get,
    AudioFormat.Wav,
    Some("recording")
  )
  private val payloads: Seq[MessagePayload] = Seq(Message.User("hello"), Message.Assistant("answer"), audio)

  payloads.foreach { payload =>
    Seq(None, Some(message.id)).foreach { parentId =>
      test(s"${payload.getClass.getSimpleName} message round-trips with parent $parentId") {
        val value = Message(data.copy(parentId = parentId, payload = payload))
        assertEquals(value.toJson.fromJson[Message], Right(value))
        assertEquals(value.data.toJson.fromJson[MessageData], Right(value.data))
        assertEquals(value.id, MessageId.from(value.data))
      }
    }
  }

  test("an omitted or null optional parent decodes to the same canonical message") {
    val explicitNull = messageJson.replace("\"timestamp\"", "\"parentId\":null,\"timestamp\"")
    assertEquals(messageJson.fromJson[Message], Right(message))
    assertEquals(explicitNull.fromJson[Message], Right(message))
    assertEquals(explicitNull.fromJson[Message].map(_.toJson), Right(messageJson))
    assert(!messageJson.contains("parentId"))
  }

  private val alterations = Seq(
    "payload text" -> data.copy(payload = Message.User("private altered text")),
    "payload type" -> data.copy(payload = Message.Assistant("héllo 🌍")),
    "author" -> data.copy(agentId = AgentId("other")),
    "parent" -> data.copy(parentId = Some(message.id)),
    "timestamp" -> data.copy(timestamp = timestamp.plusSeconds(1))
  )

  alterations.foreach { case (field, altered) =>
    test(s"decoding rejects altered $field with a constant safe error") {
      val json = s"""{"id":"$expectedId","data":${altered.toJson}}"""
      assertEquals(json.fromJson[Message], Left("(message id does not match data)"))
    }
  }

  test("decoding rejects removing or replacing a parent without recomputing the id") {
    val original = Message(data.copy(parentId = Some(message.id)))
    val replacement = MessageId.sha256("different parent")
    Seq(None, Some(replacement)).foreach { parentId =>
      val json = s"""{"id":"${MessageId.value(original.id)}","data":${data.copy(parentId = parentId).toJson}}"""
      assertEquals(json.fromJson[Message], Left("(message id does not match data)"))
    }
  }

  test("decoding rejects a well-formed but wrong id") {
    val json = messageJson.replace(expectedId, MessageId.value(MessageId.sha256("wrong id")))
    assertEquals(json.fromJson[Message], Left("(message id does not match data)"))
  }

  test("decoding validates the format of message and parent ids with the existing codec") {
    val malformedIds = Seq("", "not-an-id", expectedId.toUpperCase, expectedId.dropRight(1), s"${expectedId}0")
    malformedIds.foreach { id =>
      val malformedMessage = s"""{"id":"$id","data":$dataJson}"""
      val malformedParent = messageJson.replace("\"timestamp\"", s""""parentId":"$id","timestamp"""")
      Seq(malformedMessage, malformedParent).foreach { json =>
        val result = json.fromJson[Message]
        assert(result.isLeft)
        assert(result.swap.toOption.get.contains("artifact id must be sha256:"))
      }
    }
  }

  test("decoding rejects missing required envelope and data fields") {
    val fields = Seq(
      """"agentId":"human"""",
      """"timestamp":"2026-01-01T00:00:00Z"""",
      """"payload":{"User":{"text":"héllo 🌍"}}"""
    )
    val missingDataFields = fields.indices.map { index =>
      val incompleteData = fields.patch(index, Nil, 1).mkString("{", ",", "}")
      s"""{"id":"$expectedId","data":$incompleteData}"""
    }
    val missingEnvelopeFields = Seq(s"""{"data":$dataJson}""", s"""{"id":"$expectedId"}""")
    (missingEnvelopeFields ++ missingDataFields).foreach { json =>
      assert(json.fromJson[Message].isLeft, clues(json))
    }
  }
}
