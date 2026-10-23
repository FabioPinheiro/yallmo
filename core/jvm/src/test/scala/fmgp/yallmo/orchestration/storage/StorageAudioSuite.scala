package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.MessagePayload.AudioAttachment
import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import munit.FunSuite
import zio.*
import zio.stream.ZStream

final class StorageAudioSuite extends FunSuite {
  private val human = AgentId("human")
  private val sessionId = SessionId.unsafe("audio")
  private val bytes = Chunk[Byte](12, 34, 56, 78)
  private def audio = AudioAttachment(BlobId.fresh(), AudioFormat.Wav, Some("recording"))
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe
        .run(effect.timeoutFail(new RuntimeException("test timed out"))(10.seconds))
        .getOrThrowFiberFailure()
    }

  private def directory[A](body: Path => A): A = {
    val root = Files.createTempDirectory("yallmo-blobs-")
    try body(root)
    finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  for filesystem <- List(false, true) do {
    def fixture[A](body: Orchestrator[Any] => ZIO[Any, YallmoError, A]): A = directory { root =>
      run(for {
        instances <- RuntimeInstances.fromAgents(Chunk(new Agent[Any] {
          val id = human
          def turn(
              request: fmgp.yallmo.model.llm.AgentTurnRequest
          ): IO[YallmoError, fmgp.yallmo.model.llm.AgentTurnResponse] =
            ZIO.dieMessage("Human agent must not be invoked")
        }))
        store <-
          if filesystem then OrchestratorLive.filesystem(root, instances) else OrchestratorLive.inMemory(instances)
        _ <- store.createSession(sessionId, Chunk(human))
        result <- body(store)
      } yield result)
    }
    val backend = if filesystem then "filesystem" else "memory"

    test(s"$backend: Storage appends full blobs independently of messages and sessions") {
      directory { root =>
        run(for {
          store <- if filesystem then StorageFS.open(root) else StorageInMemory.make
          input = audio
          value = Blob(input.blobId, Chunk.fromIterable((-128 to 127).map(_.toByte)))
          _ <- store.append(value)
          loaded <- store.readBlob(input.blobId)
          sessions <- store.listSessions
          duplicate <- store.append(value).either
          candidate = value.copy(id = BlobId.fresh())
          competing = candidate.copy(bytes = candidate.bytes.reverse)
          concurrent <- store.append(candidate).either.zipPar(store.append(competing).either)
          winner <- store.readBlob(candidate.id)
          empty = Blob(BlobId.fresh(), Chunk.empty)
          _ <- store.append(empty)
          loadedEmpty <- store.readBlob(empty.id)
        } yield {
          assertEquals(loaded, value)
          assertEquals(sessions, Chunk.empty)
          assertEquals(duplicate, Left(YallmoError.BlobAlreadyExists(input.blobId)))
          assertEquals(List(concurrent._1, concurrent._2).count(_.isRight), 1)
          assertEquals(
            List(concurrent._1, concurrent._2).count(_ == Left(YallmoError.BlobAlreadyExists(candidate.id))),
            1
          )
          assert(winner.bytes == candidate.bytes || winner.bytes == competing.bytes)
          assertEquals(loadedEmpty, empty)
          if filesystem then assertEquals(Files.size(root.resolve("objects.jsonl")), 0L)
        })
      }
    }

    test(s"$backend: bytes, stream, and writer append preserve content outside message identity") {
      fixture { store =>
        val first = audio
        val second = audio
        val third = audio
        for {
          one <- store.appendWithBlobBytes(sessionId, human, first, bytes)
          _ <- store.appendWithBlobStream(sessionId, human, second, ZStream.fromChunk(bytes))
          _ <- store.appendWithBlobWriter(
            sessionId,
            human,
            third,
            writer => writer(bytes.take(2)) *> writer(bytes.drop(2))
          )
          blob <- store.readBlob(first.blobId)
          streamBlob <- store.readBlob(second.blobId)
          writerBlob <- store.readBlob(third.blobId)
          session <- store.loadSession(sessionId)
        } yield {
          assertEquals(blob.bytes, bytes)
          assertEquals(streamBlob.bytes, bytes)
          assertEquals(writerBlob.bytes, bytes)
          assertEquals(session.messages.map(_.payload), Chunk(first, second, third))
          assertEquals(session.messages.head.id, one.messages.head.id)
          session.messages.foreach(message => assertEquals(Message(message.data).id, message.id))
          assertEquals(session.messages.last.parentId, Some(session.messages(1).id))
        }
      }
    }

    test(s"$backend: message is visible before ingestion completes; buffered bytes are not stored yet") {
      fixture { store =>
        val input = audio
        for {
          started <- Promise.make[Nothing, Unit]
          finish <- Promise.make[Nothing, Unit]
          fiber <- store
            .appendWithBlobWriter(
              sessionId,
              human,
              input,
              writer => writer(bytes) *> started.succeed(()).unit *> finish.await
            )
            .fork
          _ <- started.await
          visible <- store.loadSession(sessionId)
          pending <- store.readBlob(input.blobId).either
          _ <- store.append(sessionId, human, Chunk(MessagePayload.User("while recording")))
          _ <- finish.succeed(())
          _ <- fiber.join
          complete <- store.readBlob(input.blobId)
          session <- store.loadSession(sessionId)
        } yield {
          assertEquals(visible.messages.head.payload, input)
          assertEquals(pending, Left(YallmoError.BlobMissing(input.blobId)))
          assertEquals(complete.bytes, bytes)
          assertEquals(session.messages.size, 2)
        }
      }
    }

    test(s"$backend: failures, cancellation and defects preserve the message and collected bytes") {
      fixture { store =>
        val failed = audio
        val cancelled = audio
        val broken = audio
        for {
          failure <- store
            .appendWithBlobWriter(
              sessionId,
              human,
              failed,
              writer => writer(bytes.take(2)) *> ZIO.fail(YallmoError.InvalidConfig("producer failed"))
            )
            .either
          failedBlob <- store.readBlob(failed.blobId)
          started <- Promise.make[Nothing, Unit]
          fiber <- store
            .appendWithBlobWriter(
              sessionId,
              human,
              cancelled,
              writer => writer(bytes.drop(2)) *> started.succeed(()).unit *> ZIO.never
            )
            .fork
          _ <- started.await
          _ <- fiber.interrupt
          cancelledBlob <- store.readBlob(cancelled.blobId)
          defect <- store
            .appendWithBlobWriter(sessionId, human, broken, writer => writer(bytes) *> ZIO.dieMessage("defect"))
            .exit
          brokenBlob <- store.readBlob(broken.blobId)
          session <- store.loadSession(sessionId)
        } yield {
          assertEquals(failure, Left(YallmoError.InvalidConfig("producer failed")))
          assertEquals(failedBlob.bytes, bytes.take(2))
          assertEquals(cancelledBlob.bytes, bytes.drop(2))
          assert(defect.isFailure)
          assertEquals(brokenBlob.bytes, bytes)
          assertEquals(session.messages.size, 3)
        }
      }
    }

    test(s"$backend: duplicate IDs, missing blobs, invalid payloads and expired writers are rejected") {
      fixture { store =>
        val input = audio
        for {
          savedWriter <- Promise.make[Nothing, Chunk[Byte] => IO[YallmoError, Unit]]
          _ <- store.appendWithBlobWriter(
            sessionId,
            human,
            input,
            writer => savedWriter.succeed(writer).unit *> writer(bytes)
          )
          writer <- savedWriter.await
          closed <- writer(bytes).either
          duplicate <- store.appendWithBlobBytes(sessionId, human, input, bytes.reverse).either
          missingId = BlobId.fresh()
          missing <- store.readBlob(missingId).either
          invalid <- store.appendWithBlobBytes(sessionId, human, MessagePayload.User("no reference"), bytes).either
          outsider <- store.appendWithBlobBytes(sessionId, AgentId("other"), audio, bytes).either
          stored <- store.readBlob(input.blobId)
          session <- store.loadSession(sessionId)
        } yield {
          assertEquals(closed, Left(YallmoError.BlobWriterClosed(input.blobId)))
          assertEquals(duplicate, Left(YallmoError.BlobAlreadyExists(input.blobId)))
          assertEquals(missing, Left(YallmoError.BlobMissing(missingId)))
          assert(invalid.isLeft)
          assert(outsider.isLeft)
          assertEquals(stored.bytes, bytes)
          // Message append precedes blob submission, so a refused blob does not roll the message back.
          assertEquals(session.messages.size, 2)
        }
      }
    }

    test(s"$backend: producer environments are preserved") {
      fixture { store =>
        val input = audio
        store
          .appendWithBlobStream(
            sessionId,
            human,
            input,
            ZStream.fromZIO(ZIO.service[String]).flatMap(text => ZStream.fromIterable(text.getBytes.toSeq))
          )
          .provideEnvironment(ZEnvironment("hello")) *> store.readBlob(input.blobId).map { blob =>
          assertEquals(new String(blob.bytes.toArray), "hello")
        }
      }
    }
  }

  test("Orchestrator buffers chunks in memory and appends the blob to Storage only after production") {
    directory { root =>
      val input = audio
      run(for {
        instances <- RuntimeInstances.fromAgents(Chunk(new Agent[Any] {
          val id = human
          def turn(
              request: fmgp.yallmo.model.llm.AgentTurnRequest
          ): IO[YallmoError, fmgp.yallmo.model.llm.AgentTurnResponse] =
            ZIO.dieMessage("Human agent must not be invoked")
        }))
        orchestrator <- OrchestratorLive.filesystem(root, instances)
        _ <- orchestrator.createSession(sessionId, Chunk(human))
        started <- Promise.make[Nothing, Unit]
        finish <- Promise.make[Nothing, Unit]
        fiber <- orchestrator
          .appendWithBlobWriter(
            sessionId,
            human,
            input,
            writer => writer(bytes.take(2)) *> started.succeed(()).unit *> finish.await *> writer(bytes.drop(2))
          )
          .fork
        _ <- started.await
        storage <- StorageFS.open(root)
        visible <- storage.loadSession(sessionId)
        notStored <- storage.readBlob(input.blobId).either
        _ <- ZIO.succeed(assert(Files.notExists(root.resolve("blobs").resolve(input.blobId.value))))
        _ <- finish.succeed(())
        _ <- fiber.join
        complete <- storage.readBlob(input.blobId)
      } yield {
        assertEquals(visible.messages.head.payload, input)
        assertEquals(notStored, Left(YallmoError.BlobMissing(input.blobId)))
        assertEquals(complete.bytes, bytes)
      })
    }
  }

  test("filesystem: reopen restores full blob, IDs and parents; JSONL never includes bytes") {
    directory { root =>
      val input = audio
      run(for {
        store <- StorageFS.open(root)
        _ <- store.createSession(sessionId, Chunk(human))
        original <- store.append(sessionId, human, Chunk(input))
        _ <- store.append(
          Blob(input.blobId, bytes)
        )
        _ <- store.append(sessionId, human, Chunk(MessagePayload.Assistant("transcript")))
        reopened <- StorageFS.open(root)
        blob <- reopened.readBlob(input.blobId)
        loaded <- reopened.loadSession(sessionId)
      } yield {
        assertEquals(blob.bytes, bytes)
        assertEquals(loaded.messages.head, original.messages.head)
        assertEquals(loaded.messages.last.parentId, Some(original.messages.head.id))
        val jsonl = Files.readString(root.resolve("objects.jsonl"))
        assert(!jsonl.contains("\"bytes\""))
        assert(jsonl.contains(input.blobId.value))
        val path = root.resolve("blobs").resolve(input.blobId.value)
        assert(Files.isRegularFile(path))
        assertEquals(Files.readAllBytes(path).toSeq, bytes.toSeq)
      })
    }
  }

  test(
    "filesystem: duplicate IDs survive reopen, files are raw bytes, missing content fails, and the root is private"
  ) {
    directory { root =>
      val input = audio
      run(for {
        store <- StorageFS.open(root)
        _ <- store.createSession(sessionId, Chunk(human))
        value = Blob(input.blobId, bytes)
        _ <- store.append(value)
        reopened <- StorageFS.open(root)
        duplicate <- reopened.append(value).either
        path = root.resolve("blobs").resolve(input.blobId.value)
        _ <- ZIO.attempt {
          if Files.getFileStore(root).supportsFileAttributeView("posix") then {
            import java.nio.file.attribute.PosixFilePermissions
            assertEquals(Files.getPosixFilePermissions(root), PosixFilePermissions.fromString("rwx------"))
          }
          Files.write(path, Array[Byte](1))
        }.orDie
        changed <- reopened.readBlob(input.blobId)
        _ <- ZIO.attempt(Files.delete(path)).orDie
        missing <- reopened.readBlob(input.blobId).either
      } yield {
        assertEquals(duplicate, Left(YallmoError.BlobAlreadyExists(input.blobId)))
        assertEquals(changed, Blob(input.blobId, Chunk[Byte](1)))
        assertEquals(missing, Left(YallmoError.BlobMissing(input.blobId)))
      })
    }
  }

  test("new audio envelopes verify hashes and malformed/changed references fail") {
    val input = audio
    val data = MessageData(human, None, Instant.parse("2026-01-01T00:00:00Z"), input)
    val stored = CanonicalArtifactHash.store(data)
    assertEquals(stored.value, data)
    assertEquals(CanonicalArtifactHash.verify(DecodedArtifact.Msg(stored)), Right(()))
    val tampered = stored.copy(value = data.copy(payload = input.copy(blobId = BlobId.fresh())))
    assert(CanonicalArtifactHash.verify(DecodedArtifact.Msg(tampered)).isLeft)
    val json = StorageJson.envelopeJson(DecodedArtifact.Msg(stored)).toOption.get
    assertEquals(StorageJson.decodeEnvelope(json), Right(DecodedArtifact.Msg(stored)))
    assert(StorageJson.decodeEnvelope(json.replace(input.blobId.value, "../escape")).isLeft)
    directory { root =>
      Files.writeString(
        root.resolve("objects.jsonl"),
        json.replace(input.blobId.value, BlobId.fresh().value) + "\n"
      )
      assert(run(StorageFS.open(root).either).isLeft)
    }
  }
}
