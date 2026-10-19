package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{ProviderContinuation, ProviderId}
import fmgp.yallmo.orchestration.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import munit.FunSuite
import zio.*
import zio.json.EncoderOps

final class StorageAudioSuite extends FunSuite {
  private val human = AgentId("human")
  private val transcriber = AgentId("transcriber")
  private val sessionId = SessionId.unsafe("transcription-test")
  private val audio = AudioInput(Chunk[Byte](12, 34, 56, 78), AudioFormat.Wav, 4096L, Some(1.25), Some("recording"))
  private val data = MessageData(human, None, Instant.parse("2026-01-01T00:00:00Z"), audio)

  test("canonical storage hashes live audio before projecting metadata without mutating input") {
    val stored = CanonicalArtifactHash.store(data)
    assertEquals(stored.id, MessageId.from(data))
    assertEquals(stored.value, data.redacted)
    assertNotEquals(stored.id, MessageId.from(stored.value))
    assertEquals(CanonicalArtifactHash.verify(DecodedArtifact.Msg(stored)), Right(()))
    assertEquals(audio.bytes, Chunk[Byte](12, 34, 56, 78))
  }

  test("envelope encoding and decoding both use the shared safe projection and preserve identity") {
    val live = StoredArtifact(MessageId.from(data), data)
    val encoded = StorageJson.envelopeJson(DecodedArtifact.Msg(live)).toOption.get
    val safe = DecodedArtifact.Msg(live.copy(value = data.redacted))
    assertEquals(StorageJson.decodeEnvelope(encoded), Right(safe))
    assertEquals(StorageJson.decodeEnvelope(rawEnvelope(live.id, data)), Right(safe))
    assertEquals(ujson.read(encoded)("value")("payload")("AudioInput")("bytes").arr.size, 0)
    assertEquals(live.value.payload, audio)
  }

  test("object-store returns and indexes only metadata and deduplicates a repeated live audio ID") {
    withRoot { root =>
      val (stored, indexed, repeated, reloaded) = runZIO {
        for {
          store <- StorageStore.open(root)
          stored <- store.put(data)
          indexed <- store.get(stored.id)
          repeated <- store.put(data)
          reopened <- StorageStore.open(root)
          reloaded <- reopened.get(stored.id)
        } yield (stored, indexed, repeated, reloaded)
      }
      val safe = StoredArtifact(MessageId.from(data), data.redacted)
      assertEquals(stored, safe)
      assertEquals(repeated, safe)
      assertEquals(indexed, Some(DecodedArtifact.Msg(safe)))
      assertEquals(reloaded, indexed)
      val lines = Files.readAllLines(root.resolve("objects.jsonl"), StandardCharsets.UTF_8)
      assertEquals(lines.size(), 1)
      assertEquals(ujson.read(lines.get(0))("value")("payload")("AudioInput")("bytes").arr.size, 0)
    }
  }

  test("legacy live and redacted records of the same audio ID agree on reload and later insert") {
    withRoot { root =>
      val id = MessageId.from(data)
      writeObjects(root, rawEnvelope(id, data), rawEnvelope(id, data.redacted))
      val (loaded, repeated, child, reloadedChild) = runZIO {
        for {
          store <- StorageStore.open(root)
          loaded <- store.get(id)
          repeated <- store.put(data)
          child <- store.put(data.copy(agentId = transcriber, parentId = Some(id), payload = Message.Assistant("text")))
          reopened <- StorageStore.open(root)
          reloadedChild <- reopened.get(child.id)
        } yield (loaded, repeated, child, reloadedChild)
      }
      assertEquals(loaded, Some(DecodedArtifact.Msg(StoredArtifact(id, data.redacted))))
      assertEquals(repeated, StoredArtifact(id, data.redacted))
      assertEquals(child.value.parentId, Some(id))
      assertEquals(reloadedChild, Some(DecodedArtifact.Msg(child)))
      assertEquals(Files.readAllLines(root.resolve("objects.jsonl")).size(), 3)
    }
  }

  test("audio duplicate IDs still reject conflicting metadata, author, timestamp and parent") {
    val conflicts = Seq(
      data.copy(payload = audio.copy(sizeBytes = 1L)),
      data.copy(payload = audio.copy(durationSeconds = Some(2.0))),
      data.copy(payload = audio.copy(description = Some("different"))),
      data.copy(agentId = transcriber),
      data.copy(timestamp = data.timestamp.plusSeconds(1)),
      data.copy(parentId = Some(MessageId.sha256("parent")))
    )
    conflicts.foreach { conflict =>
      withRoot { root =>
        val id = MessageId.from(data)
        writeObjects(root, rawEnvelope(id, data), rawEnvelope(id, conflict))
        val result = runZIO(StorageStore.open(root).either)
        assertFailure(result, "conflicting object")
      }
    }
  }

  test("audio hash exception does not bypass missing-parent checks on load or insert") {
    withRoot { root =>
      val orphan = data.copy(parentId = Some(MessageId.sha256("missing")))
      val inserted = runZIO(StorageStore.open(root).flatMap(_.put(orphan)).either)
      assertFailure(inserted, "is missing")
      assertEquals(Files.size(root.resolve("objects.jsonl")), 0L)
      writeObjects(root, rawEnvelope(MessageId.from(orphan), orphan))
      assertFailure(runZIO(StorageStore.open(root).either), "is missing")
    }
  }

  test("audio hash exception does not permit a self-parent root") {
    withRoot { root =>
      val id = MessageId.from(data)
      writeObjects(root, rawEnvelope(id, data.copy(parentId = Some(id))))
      assertFailure(runZIO(StorageStore.open(root).either), "is missing")
    }
  }

  private val nonAudio: Seq[MessagePayload] = Seq(
    Message.User("hello"),
    Message.Assistant("retained transcript"),
    Message.Context("label", "context"),
    Message.RagResult("docs", Chunk(RagDocument("id", "source", "text"))),
    Message.ToolResult("tool", "result"),
    Message.Reasoning(Some("summary"), ProviderContinuation(ProviderId.unsafe("provider"), None, "opaque")),
    MessagePayload.FunctionCallRequest(FunctionCall(FunctionCallId.unsafe("call-1"), "tool", "{}")),
    Message.FunctionCallOutput(FunctionCallOutput(FunctionCallId.unsafe("call-1"), "tool", "result")),
    Message.Instructions("system")
  )

  nonAudio.foreach { payload =>
    test(s"${payload.getClass.getSimpleName} still verifies and rejects tampering in an audio history") {
      withRoot { root =>
        val parent = CanonicalArtifactHash.store(data)
        val child = data.copy(agentId = transcriber, parentId = Some(parent.id), payload = payload)
        val stored = CanonicalArtifactHash.store(child)
        assertEquals(CanonicalArtifactHash.verify(DecodedArtifact.Msg(stored)), Right(()))
        writeObjects(root, rawEnvelope(parent.id, parent.value), rawEnvelope(stored.id, stored.value))
        assertEquals(runZIO(StorageStore.open(root).flatMap(_.get(stored.id))), Some(DecodedArtifact.Msg(stored)))
        val tampered = stored.copy(value = child.copy(timestamp = child.timestamp.plusSeconds(1)))
        assert(CanonicalArtifactHash.verify(DecodedArtifact.Msg(tampered)).isLeft)
        writeObjects(root, rawEnvelope(parent.id, parent.value), rawEnvelope(stored.id, tampered.value))
        assertFailure(runZIO(StorageStore.open(root).either), "hash mismatch")
      }
    }
  }

  private val malformed = Seq(
    "invalid ID" -> ((json: ujson.Value) => json("id") = ujson.Str("invalid")),
    "unknown artifact kind" -> ((json: ujson.Value) => json("kind") = ujson.Str("unknown")),
    "missing audio format" -> ((json: ujson.Value) => {
      json("value")("payload")("AudioInput").obj.remove("format"); ()
    }),
    "invalid audio bytes" -> ((json: ujson.Value) =>
      json("value")("payload")("AudioInput")("bytes") = ujson.Str("invalid")
    )
  )

  malformed.foreach { case (label, mutate) =>
    test(s"audio records retain structural validation: $label") {
      withRoot { root =>
        val json = ujson.read(rawEnvelope(MessageId.from(data), data.redacted))
        mutate(json)
        writeObjects(root, json.render())
        assertFailure(runZIO(StorageStore.open(root).either), "record 1:")
      }
    }
  }

  Seq("filesystem", "in-memory").foreach { backend =>
    test(s"$backend mixed-history append, load, fork and returned views retain no audio bytes") {
      withRoot { root =>
        val secondAudio = audio.copy(bytes = Chunk[Byte](90, 91), sizeBytes = 2048L, description = None)
        val views = runZIO {
          for {
            storage <- if backend == "filesystem" then StorageFS.open(root) else StorageInMemory.make
            _ <- storage.createSession(sessionId, Chunk(human, transcriber))
            first <- storage.append(sessionId, human, Chunk(audio))
            transcript <- storage.append(sessionId, transcriber, Chunk(Message.Assistant("retained transcript")))
            next <- storage.append(sessionId, human, Chunk(Message.User("follow-up"), secondAudio))
            loaded <- storage.loadSession(sessionId)
            forked <- storage.fork(sessionId, SessionId.unsafe("copy"))
            added <- storage.addSessionAgent(sessionId, AgentId("reviewer"))
            reopened <- if backend == "filesystem" then StorageFS.open(root) else ZIO.succeed(storage)
            reloaded <- reopened.loadSession(sessionId)
            appended <- reopened.append(sessionId, transcriber, Chunk(Message.Assistant("second transcript")))
            forkLoaded <- reopened.loadSession(SessionId.unsafe("copy"))
            listed <- reopened.listSessions
            _ <- ZIO.succeed(assertEquals(listed.map(_.messageCount), Chunk(4L, 5L)))
          } yield Seq(first, transcript, next, loaded, forked, added, reloaded, appended, forkLoaded)
        }
        val expected = Chunk[MessagePayload](
          audio.redacted,
          Message.Assistant("retained transcript"),
          Message.User("follow-up"),
          secondAudio.redacted,
          Message.Assistant("second transcript")
        )
        views.foreach { session =>
          assertEquals(session.messages.map(_.payload), expected.take(session.messages.size))
          session.messages.zipWithIndex.foreach { case (message, index) =>
            assertEquals(message.parentId, session.messages.take(index).lastOption.map(_.id))
            val livePayload = message.payload match {
              case _: AudioInput => if index == 0 then audio else secondAudio
              case other         => other
            }
            assertEquals(message.id, MessageId.from(message.data.copy(payload = livePayload)))
            assertEquals(message.agentId, if index == 1 || index == 4 then transcriber else human)
          }
        }
        assertEquals(views(1).messages.head, views.head.messages.head)
        assertEquals(views(6).messages, views(2).messages)
        assertEquals(views(8).messages, views(2).messages)
        assertEquals(audio.bytes, Chunk[Byte](12, 34, 56, 78))
        assertEquals(secondAudio.bytes, Chunk[Byte](90, 91))
        if backend == "filesystem" then {
          val lines = Files.readAllLines(root.resolve("objects.jsonl"), StandardCharsets.UTF_8)
          assertEquals(lines.size(), 5)
          Seq(0, 3).foreach { index =>
            assertEquals(ujson.read(lines.get(index))("value")("payload")("AudioInput")("bytes").arr.size, 0)
          }
        }
      }
    }
  }

  private def rawEnvelope(id: MessageId, value: MessageData): String =
    s"""{"id":"${MessageId.value(id)}","kind":"msg","value":${value.toJson}}"""

  private def writeObjects(root: Path, lines: String*): Unit = {
    Files.writeString(root.resolve("objects.jsonl"), lines.mkString("", "\n", "\n"), StandardCharsets.UTF_8)
    ()
  }

  private def assertFailure[A](result: Either[YallmoError, A], expected: String): Unit =
    result match {
      case Left(error: YallmoError.StorageFailure) => assert(error.getMessage.contains(expected))
      case _                                       => fail("expected a storage validation failure")
    }

  private def withRoot(test: Path => Unit): Unit = {
    val root = Files.createTempDirectory("yallmo-storage-audio-")
    try test(root)
    finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  private def runZIO[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
