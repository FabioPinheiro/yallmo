package fmgp.yallmo.tool.sdlc

import fmgp.yallmo.fs.*
import fmgp.yallmo.model.ToolKey
import fmgp.yallmo.orchestration.ExternalToolCall
import fmgp.yallmo.tool.sdlc.llm.{PlanIntentSimplifiedModel, PlanTaskSimplifiedModel}
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import munit.FunSuite
import zio.*
import zio.json.*
import zio.stream.ZStream

final class SdlcSafetySuite extends FunSuite {
  private val time = Instant.parse("2026-01-01T00:00:00Z")

  test("stale task/intent replacements and stale mixed batches cannot undo another agent's changes") {
    withRoot { (_, fs) =>
      run {
        for {
          service <- SdlcService.make(fs, fs)
          tasks <- service.setTasks(List(PlanTaskSimplifiedModel("", "One"), PlanTaskSimplifiedModel("", "Two")))
          intents <- service.setIntents(List(PlanIntentSimplifiedModel("", "Outcome")))
          taskA <- service.readTasks(List("T-1"))
          taskB <- service.readTasks(List("T-1"))
          first <- service.setTasks(List(taskA.head.copy(text = "First agent's edit")))
          before <- fs.readUtf8(PlanTaskStorage.JournalPath)
          stale <- service
            .setTasks(
              List(taskB.head.copy(assumptions = List("Stale belief")), tasks(1).copy(text = "Must not commit"))
            )
            .either
          after <- fs.readUtf8(PlanTaskStorage.JournalPath)
          secondTask <- service.readTasks(List("T-2"))
          _ <- service.setIntents(List(intents.head.copy(text = "New outcome")))
          staleIntent <- service.setIntents(List(intents.head.copy(archived = true))).either
          missingRevision <- service.setTasks(List(PlanTaskSimplifiedModel("T-1", "No version"))).either
          // Re-read and preserve the other agent's changes, then the edit succeeds.
          fresh <- service.readTasks(List("T-1"))
          merged <- service.setTasks(List(fresh.head.copy(assumptions = List("Checked belief"))))
        } yield {
          assert(stale.isLeft)
          assert(staleIntent.isLeft)
          assert(missingRevision.isLeft)
          assertEquals(before, after)
          assertEquals(secondTask.head, tasks(1))
          assertEquals(first.head.expectedRevision, 2L)
          assertEquals(merged.head.expectedRevision, 3L)
          assertEquals(merged.head.text, "First agent's edit")
        }
      }
    }
  }

  test("two concurrent replacements from the same read admit exactly one writer") {
    withRoot { (_, fs) =>
      run {
        for {
          service <- SdlcService.make(fs, fs)
          created <- service.setTasks(List(PlanTaskSimplifiedModel("", "Task")))
          results <- ZIO.foreachPar(List("Agent A", "Agent B"))(text =>
            service.setTasks(List(created.head.copy(text = text))).either
          )
          current <- service.readTasks(List("T-1"))
        } yield {
          assertEquals(results.count(_.isRight), 1)
          assertEquals(results.count(_.isLeft), 1)
          assertEquals(current.head.expectedRevision, 2L)
        }
      }
    }
  }

  test("legacy histories infer versions and continue safely across checkpoint and reopen") {
    withRoot { (_, fs) =>
      val legacyTask = PlanTask("T-1", "Old", time, time)
      val legacyIntent = PlanIntent("I-1", "Old outcome", time, time)
      def legacy[A: JsonEncoder](value: A): String = {
        val array = ujson.read(List(value).toJson)
        array.arr.foreach(record => { val _ = record.obj.remove("revision") })
        array.render() + "\n"
      }
      run {
        for {
          _ <- fs.createDirectories("sdlc")
          _ <- fs.writeUtf8(
            PlanTaskStorage.JournalPath,
            legacy(legacyTask) + legacy(legacyTask.copy(text = "Second")),
            WriteMode.CreateNew
          )
          _ <- fs.writeUtf8(
            PlanIntentStorage.JournalPath,
            legacy(legacyIntent) + legacy(legacyIntent.copy(text = "Second outcome")),
            WriteMode.CreateNew
          )
          service <- SdlcService.make(fs, fs)
          tasks <- service.readTasks(List("T-1"))
          intents <- service.readIntents(List("I-1"))
          _ <- service.checkpoint
          reopened <- SdlcService.make(fs, fs)
          saved <- reopened.setTasks(List(tasks.head.copy(text = "Third")))
          savedIntent <- reopened.setIntents(List(intents.head.copy(text = "Third outcome")))
          full <- reopened.listTasks
        } yield {
          assertEquals(tasks.head.expectedRevision, 2L)
          assertEquals(intents.head.expectedRevision, 2L)
          assertEquals(saved.head.expectedRevision, 3L)
          assertEquals(savedIntent.head.expectedRevision, 3L)
          assertEquals(full.head.createdAt, time)
        }
      }
    }
  }

  test("discovery is bounded, searchable, paginated, and hides archives by default") {
    withRoot { (_, fs) =>
      run {
        for {
          service <- SdlcService.make(fs, fs)
          _ <- service.setTasks(
            List(
              PlanTaskSimplifiedModel("", "First title\nSearchable details"),
              PlanTaskSimplifiedModel("", "Archived title", archived = true),
              PlanTaskSimplifiedModel("", "x" * 800)
            )
          )
          _ <- service.setIntents(List(PlanIntentSimplifiedModel("", "Outcome")))
          first <- service.discover(limit = 2)
          next <- service.discover(limit = 2, afterId = first.nextAfterId)
          searched <- service.discover(query = "SEARCHABLE")
          archived <- service.discover(query = "T-2", includeArchived = true)
          tools = SdlcTools.make(service)
          result <- tools
            .find(_.definition.key == ToolKey.SdlcList)
            .get
            .execute(ExternalToolCall(ToolKey.SdlcList, "{}"))
          _ <- ZIO.foreachDiscard(
            List(
              service.discover(limit = 0),
              service.discover(limit = 101),
              service.discover(query = "x" * 201),
              service.discover(afterId = Some("../escape"))
            )
          )(request => request.either.map(value => assert(value.isLeft)))
        } yield {
          assertEquals(first.items.map(_.id), List("I-1", "T-1"))
          assertEquals(first.nextAfterId, Some("T-1"))
          assertEquals(next.items.map(_.id), List("T-3"))
          assertEquals(next.nextAfterId, None)
          assertEquals(searched.items.map(_.title), List("First title"))
          assertEquals(archived.items.map(_.id), List("T-2"))
          assertEquals(archived.items.head.expectedRevision, 1L)
          assert(next.items.head.title.length <= 160)
          assertEquals(result.text.fromJson[PlanDiscovery].map(_.items.size), Right(3))
        }
      }
    }
  }

  test("completing/archiving work frees active capacity without removing or recycling its ID") {
    withRoot { (_, fs) =>
      val tasks = (1 to 1000).map(n => PlanTask(s"T-$n", "Task", time, time, revision = 1L)).toList
      val intents = (1 to 1000).map(n => PlanIntent(s"I-$n", "Intent", time, time, revision = 1L)).toList
      def journal[A: JsonEncoder](records: List[A]): String = records.grouped(100).map(_.toJson + "\n").mkString
      run {
        for {
          _ <- fs.createDirectories("sdlc")
          _ <- fs.writeUtf8(PlanTaskStorage.JournalPath, journal(tasks), WriteMode.CreateNew)
          _ <- fs.writeUtf8(PlanIntentStorage.JournalPath, journal(intents), WriteMode.CreateNew)
          service <- SdlcService.make(fs, fs)
          fullTasks <- service.setTasks(List(PlanTaskSimplifiedModel("", "Over capacity"))).either
          fullIntents <- service.setIntents(List(PlanIntentSimplifiedModel("", "Over capacity"))).either
          _ <- service.setTasks(List(tasks.head.toSimplifiedModel.copy(state = TaskState.Done)))
          _ <- service.setIntents(List(intents.head.toSimplifiedModel.copy(archived = true)))
          _ <- service.checkpoint
          added <- service.setTasks(List(PlanTaskSimplifiedModel("", "New task")))
          addedIntent <- service.setIntents(List(PlanIntentSimplifiedModel("", "New intent")))
          old <- service.readIntents(List("I-1"))
        } yield {
          assert(fullTasks.isLeft)
          assert(fullIntents.isLeft)
          assertEquals(added.head.id, "T-1001")
          assertEquals(addedIntent.head.id, "I-1001")
          assert(old.head.archived)
          assertEquals(old.head.expectedRevision, 2L)
        }
      }
    }
  }

  test("current-state size is bounded before writing, leaving checkpoint headroom") {
    withRoot { (root, fs) =>
      val large = PlanTaskSimplifiedModel("", "Large task", assumptions = List.fill(100)("x" * 2000))
      run {
        for {
          storage <- PlanTaskStorage.make(fs, fs)
          rejected <- storage.set(List.fill(11)(large)).either
          current <- storage.listTasks
        } yield {
          assert(rejected.isLeft)
          assertEquals(current, Nil)
          assert(!Files.exists(root.resolve(PlanTaskStorage.JournalPath)))
        }
      }
    }
  }

  test("oversized legacy content can shrink incrementally across checkpoint and reopen") {
    withRoot { (_, fs) =>
      val original = (1 to 1000).map(n => PlanIntent(s"I-$n", "x" * 3000, time, time)).toList
      val legacy = original
        .grouped(100)
        .map { batch =>
          val array = ujson.read(batch.toJson)
          array.arr.foreach(record => { val _ = record.obj.remove("revision") })
          array.render() + "\n"
        }
        .mkString
      run {
        for {
          _ <- fs.createDirectories("sdlc")
          _ <- fs.writeUtf8(PlanIntentStorage.JournalPath, legacy, WriteMode.CreateNew)
          service <- SdlcService.make(fs, fs)
          first <- service.readIntents((1 to 100).map(n => s"I-$n").toList)
          reduced <- service.setIntents(first.map(_.copy(text = "Short")))
          stillLarge <- service.listIntents
          _ <- service.checkpoint
          reopened <- SdlcService.make(fs, fs)
          restored <- reopened.listIntents
          growth <- reopened.setIntents(List(reduced.head.copy(text = "y" * 1500))).either
          _ <- ZIO.foreachDiscard(1 to 3) { batch =>
            for {
              records <- reopened.readIntents((batch * 100 + 1 to batch * 100 + 100).map(n => s"I-$n").toList)
              _ <- reopened.setIntents(records.map(_.copy(text = "Short")))
            } yield ()
          }
          smaller <- reopened.listIntents
        } yield {
          assert(legacy.getBytes("UTF-8").length > PlanJournal.MaxCurrentBytes)
          assert(stillLarge.toJson.getBytes("UTF-8").length > PlanJournal.MaxCurrentBytes)
          assertEquals(reduced.head.expectedRevision, 2L)
          assertEquals(restored, stillLarge)
          assert(growth.isLeft)
          assert(smaller.toJson.getBytes("UTF-8").length < PlanJournal.MaxCurrentBytes)
        }
      }
    }
  }

  test("checkpoint restores exhausted journal capacity and retains exact historical data") {
    withRoot { (root, fs) =>
      val original = (1 to PlanTaskStorage.MaxRecords).map { n =>
        List(PlanTask("T-1", s"Version $n", time, time, revision = n.toLong)).toJson + "\n"
      }.mkString
      run {
        for {
          _ <- fs.createDirectories("sdlc")
          _ <- fs.writeUtf8(PlanTaskStorage.JournalPath, original, WriteMode.CreateNew)
          storage <- PlanTaskStorage.make(fs, fs)
          current <- storage.readTasks(List("T-1"))
          full <- storage.set(List(current.head.copy(text = "Next version"))).either
          _ <- storage.checkpoint
          saved <- storage.set(List(current.head.copy(text = "Next version")))
          files <- fs.list("sdlc/history")
          backup <- fs.readUtf8("sdlc/history/" + files.head)
        } yield {
          assert(full.isLeft)
          assertEquals(saved.head.expectedRevision, PlanTaskStorage.MaxRecords.toLong + 1)
          assertEquals(backup, original)
          assert(!Files.exists(root.resolve(PlanTaskStorage.JournalPath + ".maintenance")))
        }
      }
    }
  }

  test("every checkpoint I/O failure either leaves the original usable or blocks reads behind its marker") {
    var steps = 0
    withRoot { (_, fs) =>
      run {
        for {
          _ <- seed(fs)
          proxy = new FaultFs(fs)
          storage <- PlanTaskStorage.make(proxy, proxy)
          _ <- storage.checkpoint
        } yield steps = proxy.calls.size
      }
    }
    assert(steps >= 15, "Must exercise marker, backup, verification, replacement and marker removal")
    (1 to steps).foreach { failure =>
      withRoot { (root, fs) =>
        run {
          for {
            _ <- seed(fs)
            proxy = new FaultFs(fs, failAt = failure)
            storage <- PlanTaskStorage.make(proxy, proxy)
            failed <- storage.checkpoint.either
            reopened <- PlanTaskStorage.make(fs, fs)
            current <- reopened.listTasks.either
          } yield {
            assert(failed.isLeft, clues(failure, proxy.calls))
            if Files.exists(root.resolve(PlanTaskStorage.JournalPath + ".maintenance")) then
              assert(current.isLeft, clues(failure, proxy.calls))
            else assertEquals(current.map(_.map(_.text)), Right(List("Second")))
          }
        }
      }
    }
  }

  test("an uncertain move cannot turn a missing primary into an empty project or recycle IDs") {
    withRoot { (root, fs) =>
      run {
        for {
          _ <- seed(fs)
          proxy = new FaultFs(fs, losePrimaryOnMove = true)
          storage <- PlanTaskStorage.make(proxy, proxy)
          failed <- storage.checkpoint.either
          reopened <- PlanTaskStorage.make(fs, fs)
          read <- reopened.listTasks.either
          create <- reopened.set(List(PlanTaskSimplifiedModel("", "Do not allocate T-1 again"))).either
          backups <- fs.list("sdlc/history")
          backup <- fs.readUtf8("sdlc/history/" + backups.head)
        } yield {
          assert(failed.isLeft && read.isLeft && create.isLeft)
          assert(!Files.exists(root.resolve(PlanTaskStorage.JournalPath)))
          assert(backup.contains("Second"))
          assert(Files.exists(root.resolve(PlanTaskStorage.JournalPath + ".maintenance")))
        }
      }
    }
  }

  private def seed(fs: FileSystem): IO[FsError, Unit] =
    fs.createDirectories("sdlc") *> fs.writeUtf8(
      PlanTaskStorage.JournalPath,
      List(PlanTask("T-1", "First", time, time)).toJson + "\n" +
        List(PlanTask("T-1", "Second", time, time)).toJson + "\n",
      WriteMode.CreateNew
    )

  /** Fail once at each externally visible I/O step; finalizer closes still delegate to the real scoped writer. */
  private final class FaultFs(fs: FileSystem, failAt: Int = -1, losePrimaryOnMove: Boolean = false)
      extends FsRead
      with FsWrite {
    var calls = Vector.empty[String]
    private def step(operation: String): IO[FsError, Unit] = ZIO.suspendSucceed {
      calls :+= operation
      if calls.size == failAt then ZIO.fail(FsError.IoFailure("injected", operation)) else ZIO.unit
    }
    def readBytes(path: String): ZStream[Any, FsError, Byte] =
      ZStream.fromZIO(step("read:" + path)).drain ++ fs.readBytes(path)
    def list(path: String): IO[FsError, Chunk[String]] = step("list:" + path) *> fs.list(path)
    def openUtf8Writer(path: String, mode: WriteMode): ZIO[Scope, FsError, FsUtf8Writer] =
      (step("open:" + path) *> fs.openUtf8Writer(path, mode)).map { writer =>
        new FsUtf8Writer {
          def write(text: String): IO[FsError, Unit] = step("write:" + path) *> writer.write(text)
          def flush: IO[FsError, Unit] = writer.flush
          def sync: IO[FsError, Unit] = step("sync:" + path) *> writer.sync
        }
      }
    def createDirectories(path: String): IO[FsError, Unit] = step("mkdir:" + path) *> fs.createDirectories(path)
    def deleteFile(path: String): IO[FsError, Unit] = step("delete:" + path) *> fs.deleteFile(path)
    def deleteDirectory(path: String, mode: DirectoryDeleteMode): IO[FsError, Unit] = fs.deleteDirectory(path, mode)
    def move(source: String, destination: String, mode: MoveMode): IO[FsError, Unit] =
      step(
        "move:" + source
      ) *> (if losePrimaryOnMove then
              fs.deleteFile(destination) *> ZIO.fail(FsError.IoFailure(destination, "Uncertain move"))
            else fs.move(source, destination, mode))
    def writeBytes[R, E](path: String, bytes: ZStream[R, E, Byte], mode: WriteMode): ZIO[R, E | FsError, Unit] =
      fs.writeBytes(path, bytes, mode)
  }

  private def withRoot(test: (Path, FileSystem) => Unit): Unit = {
    val root = Files.createTempDirectory("yallmo-sdlc-safety-").toRealPath()
    try test(root, run(FileSystem.open(root.toString)))
    finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
