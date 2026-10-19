package fmgp.yallmo.model

import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, LlmStopReason}
import munit.FunSuite
import zio.{Chunk, ZIO}
import zio.json.{DecoderOps, EncoderOps}

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

final class AudioInputSuite extends FunSuite {
  private val bytes = Chunk.fromArray("private-audio-sentinel".getBytes(StandardCharsets.UTF_8))
  private val audio = AudioInput(bytes, AudioFormat.Wav, bytes.size.toLong, Some(1.25), Some("recording"))
  private val timestamp = Instant.parse("2026-01-01T00:00:00Z")

  test("AudioInput is a direct user payload with legacy import and two-argument construction") {
    val legacy: AudioInput = AudioInput(bytes, AudioFormat.Wav)
    val payload: MessagePayload = legacy
    assertEquals(payload.role, "user")
    assertEquals(legacy.sizeBytes, bytes.size.toLong)
    assertEquals(legacy.durationSeconds, None)
    assertEquals(legacy.description, None)
    assertEquals(legacy.bytes, bytes)
  }

  test("live and metadata-only audio codecs preserve original metadata without double wrapping") {
    val payload: MessagePayload = audio
    assertEquals(payload.toJson.fromJson[MessagePayload], Right(payload))
    assertEquals(audio.toJson.fromJson[AudioInput], Right(audio))
    assert(payload.toJson.startsWith("{\"AudioInput\":{"))
    assertEquals(AudioFormat.Wav.toJson, "\"wav\"")
    assertEquals("\"wav\"".fromJson[AudioFormat], Right(AudioFormat.Wav))
    assert("\"unsupported\"".fromJson[AudioFormat].isLeft)

    val restored = audio.redacted
    assertEquals(restored.toJson.fromJson[AudioInput], Right(restored))
    assertEquals((restored: MessagePayload).toJson.fromJson[MessagePayload], Right(restored))
    val minimal = AudioInput(Chunk.empty, AudioFormat.Wav, sizeBytes = 100L)
    assertEquals(minimal.toJson.fromJson[AudioInput], Right(minimal))
  }

  test("contentStr and toString render metadata only, including through message envelopes") {
    val data = MessageData(AgentId("human"), None, timestamp, audio)
    val base64 = Base64.getEncoder.encodeToString(bytes.toArray)
    Seq(audio.contentStr, audio.toString, data.toString, Message(data).toString).foreach { rendered =>
      assert(!rendered.contains("private-audio-sentinel"))
      assert(!rendered.contains(base64))
      assert(!rendered.contains(bytes.toString))
      assert(rendered.contains("format=wav"))
      assert(rendered.contains(s"sizeBytes=${bytes.size}"))
      assert(rendered.contains("durationSeconds=1.25"))
      assert(rendered.contains("description=recording"))
    }
    assertEquals(AudioInput(bytes, AudioFormat.Wav).contentStr, s"AudioInput(format=wav, sizeBytes=${bytes.size})")
  }

  test("redacted projections are nonmutating, idempotent and preserve identity, parents and metadata") {
    val parent = MessageId.sha256("parent")
    val data = MessageData(AgentId("human"), Some(parent), timestamp, audio)
    val message = Message(data)
    val retained = message.redacted
    val metadata = audio.copy(bytes = Chunk.empty)
    assertEquals(audio.redacted, metadata)
    assertEquals(audio.redacted.redacted, metadata)
    assertEquals((audio: MessagePayload).redacted, metadata)
    assertEquals(data.redacted, data.copy(payload = metadata))
    assertEquals(retained, message.copy(data = data.redacted))
    assertEquals(retained.redacted, retained)
    assertEquals(retained.id, message.id)
    assertEquals(retained.parentId, Some(parent))
    assertEquals(retained.timestamp, timestamp)
    assertEquals(retained.agentId, AgentId("human"))
    assertEquals(audio.bytes, bytes)
    assertEquals(message.payload, audio)
    assertEquals(MessageId.from(data), message.id)
    assertNotEquals(MessageId.from(data.redacted), message.id)
    assertNotEquals(MessageId.from(data.copy(payload = audio.copy(bytes = bytes :+ 1.toByte))), message.id)
    assertEquals(data.redacted.toJson.fromJson[MessageData], Right(data.redacted))
    assert(data.redacted.toJson.contains("\"bytes\":[]"))
  }

  test("AudioAgent retains the Agent turn request/response contract") {
    val agent: AudioAgent[Any] = new AudioAgent[Any] {
      override val id: AgentId = AgentId("audio")
      override def turn(request: AgentTurnRequest): ZIO[Any, YallmoError, AgentTurnResponse] =
        ZIO.succeed(AgentTurnResponse(request.input, LlmStopReason.Stop))
    }
    val generalAgent: Agent[Any] = agent
    assertEquals(generalAgent.id, AgentId("audio"))
    val request = AgentTurnRequest("audio-model", Chunk(audio, MessagePayload.Instructions("Transcribe")))
    assertEquals(request.validate, Right(request))
    // Empty bytes are valid retained model data; only the live-send provider boundary rejects them.
    val metadataOnlyRequest = request.copy(input = Chunk(audio.redacted, MessagePayload.Instructions("Transcribe")))
    assertEquals(metadataOnlyRequest.validate, Right(metadataOnlyRequest))
  }
}
