package fmgp.yallmo.model

import fmgp.yallmo.model.MessagePayload.AudioAttachment
import java.time.Instant
import munit.FunSuite
import zio.Chunk
import zio.json.*
import fmgp.yallmo.model.llm.AgentTurnRequest

final class AudioAttachmentSuite extends FunSuite {
  private val audio = AudioAttachment(BlobId.fresh(), AudioFormat.Wav, Some("recording"))

  test("audio attachments round-trip references without bytes") {
    val payload: MessagePayload = audio
    assertEquals(payload.toJson.fromJson[MessagePayload], Right(payload))
    assert(!payload.toJson.contains("bytes"))
    assert(payload.toJson.contains("\"blobId\":\"" + audio.blobId.value + "\""))
    assertEquals(payload.blobIds.size, 1)
    assertEquals(payload.role, "user")
  }

  test("blob IDs exist before bytes, are unique canonical UUIDs, and reject paths") {
    val other = BlobId.fresh()
    assertNotEquals(audio.blobId, other)
    assertEquals(BlobId.from(audio.blobId.value), Right(audio.blobId))
    List("../data", "", "00000000-0000-0000-0000-00000000000A", "1-1-1-1-1").foreach { value =>
      assert(BlobId.from(value).isLeft)
    }
  }

  test("message identity hashes only the immutable envelope and reference") {
    val data = MessageData(AgentId("human"), None, Instant.parse("2026-01-01T00:00:00Z"), audio)
    val message = Message(data)
    assertEquals(Message(data.toJson.fromJson[MessageData].toOption.get).id, message.id)
    assertNotEquals(Message(data.copy(payload = audio.copy(blobId = BlobId.fresh()))).id, message.id)
    assertEquals(MessageId.from(MessageId.value(message.id)), Right(message.id))
    assert(audio.contentStr.contains(audio.blobId.value))
    assert(audio.contentStr.startsWith("AudioAttachment("))
    assert((audio: MessagePayload).toJson.startsWith("{\"AudioAttachment\":"))
    assert(!data.toString.contains("Chunk("))
  }

  test("full blob rendering does not dump bytes") {
    val blob = Blob(audio.blobId, Chunk[Byte](12, 34, 56))
    assert(!blob.toString.contains("Chunk("))
    assertEquals(blob.bytes.size, 3)
  }

  test("reference-only audio remains a direct payload in the shared request contract") {
    val request = AgentTurnRequest("audio-model", Chunk(audio, MessagePayload.Instructions("Transcribe")))
    assertEquals(request.validate, Right(request))
    assertEquals(AudioAttachment(audio.blobId, AudioFormat.Wav).description, None)
  }
}
