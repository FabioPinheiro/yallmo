package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.*
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import munit.FunSuite
import zio.*

final class OrchestratorListingSuite extends FunSuite {
  private val author = AgentId("human")
  private def agent(name: String): Agent[Any] = new Agent[Any] {
    val id: AgentId = AgentId(name)
    def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
      ZIO.dieMessage("Listing must not execute agents")
  }

  for filesystem <- List(false, true) do
    test(s"${if filesystem then "filesystem" else "memory"}: lists unique messages, registry IDs and stored blobs") {
      directory { root =>
        run(for {
          registry <- RuntimeInstances.fromAgents(Chunk(agent("human"), agent("unused")))
          orchestrator <-
            if filesystem then OrchestratorLive.filesystem(root, registry) else OrchestratorLive.inMemory(registry)
          main = SessionId.unsafe("main")
          fork = SessionId.unsafe("fork")
          _ <- orchestrator.createSession(main, Chunk(author))
          shared <- orchestrator.append(main, author, Chunk(MessagePayload.User("shared")))
          _ <- orchestrator.fork(main, fork)
          _ <- orchestrator.append(main, author, Chunk(MessagePayload.Assistant("main branch")))
          _ <- orchestrator.append(fork, author, Chunk(MessagePayload.Assistant("other branch")))
          blobId = BlobId.fresh()
          _ <- orchestrator.appendWithBlobBytes(
            main,
            author,
            MessagePayload.AudioAttachment(blobId, AudioFormat.Wav),
            Chunk[Byte](1, 2)
          )
          messages <- orchestrator.listMessages
          agents <- orchestrator.listAgentIds
          blobs <- orchestrator.listBlobIds
          reopenedMessages <-
            if filesystem then OrchestratorLive.filesystem(root, registry).flatMap(_.listMessages)
            else orchestrator.listMessages
        } yield {
          assertEquals(agents.map(AgentId.value), Chunk("human", "unused"))
          assertEquals(messages.size, 4)
          assertEquals(messages.map(_.id).distinct.size, 4)
          assertEquals(messages.count(_.id == shared.messages.head.id), 1)
          assertEquals(messages.map(_.timestamp).toList, messages.map(_.timestamp).toList.sorted)
          assertEquals(blobs, Chunk(blobId))
          assertEquals(reopenedMessages, messages)
        })
      }
    }

  test("filesystem listings include unreferenced objects and blobs, ignoring unrelated files and directories") {
    directory { root =>
      run(for {
        storage <- StorageFS.open(root)
        objects <- StorageStore.open(root)
        orphan <- objects.put(MessageData(author, None, Instant.EPOCH, MessagePayload.User("no session")))
        blobId = BlobId.fresh()
        _ <- storage.append(Blob(blobId, Chunk.empty))
        _ <- ZIO.attemptBlocking {
          Files.writeString(root.resolve("blobs/not-a-blob"), "unrelated")
          Files.createDirectory(root.resolve("blobs").resolve(BlobId.fresh().value))
        }
        registry <- RuntimeInstances.fromAgents(Chunk.empty[Agent[Any]])
        orchestrator <- OrchestratorLive.filesystem(root, registry)
        sessions <- orchestrator.listSessions
        messages <- orchestrator.listMessages
        blobs <- orchestrator.listBlobIds
      } yield {
        assertEquals(sessions, Chunk.empty)
        assertEquals(messages.map(_.id), Chunk(orphan.id))
        assertEquals(blobs, Chunk(blobId))
      })
    }
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def directory[A](body: Path => A): A = {
    val root = Files.createTempDirectory("yallmo-listing-")
    try body(root)
    finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }
}
