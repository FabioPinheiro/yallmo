package fmgp.yallmo.tool.sdlc

import fmgp.yallmo.fs.{FileSystem, WriteMode}
import fmgp.yallmo.model.{ToolKey, YallmoError}
import fmgp.yallmo.orchestration.ExternalToolCall
import fmgp.yallmo.tool.sdlc.llm.{PlanIntentSimplifiedModel, PlanTaskSimplifiedModel}
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import munit.FunSuite
import zio.*
import zio.json.*

final class SdlcServiceSuite extends FunSuite {
  test("empty full-state reads and no-op mutations do not create journals") {
    withRoot { (root, fs) =>
      run {
        for {
          service <- SdlcService.make(fs, fs)
          tasks <- service.listTasks
          intents <- service.listIntents
          saved <- service.setIntents(Nil)
          read <- service.readIntents(Nil)
        } yield {
          assertEquals(tasks, Nil)
          assertEquals(intents, Nil)
          assertEquals(saved, Nil)
          assertEquals(read, Nil)
          assert(!Files.exists(root.resolve("sdlc")))
        }
      }
    }
  }

  test("intent snapshots persist with metadata and task dependencies while tools return simplified models") {
    withRoot { (_, fs) =>
      run {
        for {
          service <- SdlcService.make(fs, fs)
          tasks <- service.setTasks(List(PlanTaskSimplifiedModel(PlanTaskId.New, "Investigate")))
          saved <- service.setIntents(
            List(
              PlanIntentSimplifiedModel(
                "",
                "Reliable authentication\nFailures stay below 0.1%",
                dependsOn = tasks.map(_.id.value)
              )
            )
          )
          _ <- service.setTasks(List(tasks.head.copy(contributesTo = saved.map(_.id))))
          intents <- service.listIntents
          fullTasks <- service.listTasks
          reopened <- SdlcService.make(fs, fs)
          read <- reopened.readIntents(List("I-1"))
          full <- reopened.listIntents
        } yield {
          assertEquals(saved.head.id, "I-1")
          assertEquals(intents.map(_.toSimplifiedModel), saved)
          assertEquals(intents.head.createdAt, intents.head.updatedAt)
          assertEquals(fullTasks.head.contributesTo, List("I-1"))
          assertEquals(read, saved)
          assertEquals(full, intents)
          assert(full.toJson.contains("createdAt"))
          assert(!read.toJson.contains("createdAt"))
        }
      }
    }
  }

  test("intent replacement preserves creation timestamps, resets defaults and keeps other intents") {
    withRoot { (_, fs) =>
      run {
        for {
          service <- SdlcService.make(fs, fs)
          task <- service.setTasks(List(PlanTaskSimplifiedModel(PlanTaskId.New, "Task")))
          created <- service.setIntents(
            List(
              PlanIntentSimplifiedModel("", "First", task.map(_.id.value), archived = true),
              PlanIntentSimplifiedModel("", "Second")
            )
          )
          before <- service.listIntents
          updated <- service.setIntents(
            List(
              PlanIntentSimplifiedModel(created.head.id, "Revised", expectedRevision = created.head.expectedRevision)
            )
          )
          after <- service.listIntents
          history <- fs.read[List[PlanIntent]](PlanIntentStorage.JournalPath).runCollect
          _ <- service.setIntents(updated)
          retry <- fs.read[List[PlanIntent]](PlanIntentStorage.JournalPath).runCollect
        } yield {
          assertEquals(after.head.createdAt, before.head.createdAt)
          assert(!after.head.updatedAt.isBefore(before.head.updatedAt))
          assertEquals(after.head.dependsOn, Nil)
          assert(!after.head.archived)
          assertEquals(after(1), before(1))
          assertEquals(history, retry)
          assertEquals(history.size, 2)
        }
      }
    }
  }

  test("unknown cross-kind references fail before either journal is mutated") {
    withRoot { (root, fs) =>
      run {
        for {
          service <- SdlcService.make(fs, fs)
          intent <- service.setIntents(List(PlanIntentSimplifiedModel("", "Known outcome")))
          before <- fs.readUtf8(PlanIntentStorage.JournalPath)
          badIntent <- service.setIntents(List(intent.head.copy(dependsOn = List("T-99")))).either
          badTask <- service
            .setTasks(
              List(PlanTaskSimplifiedModel(PlanTaskId.New, "Unknown contribution", contributesTo = List("I-99")))
            )
            .either
          after <- fs.readUtf8(PlanIntentStorage.JournalPath)
        } yield {
          assert(badIntent.isLeft)
          assert(badTask.isLeft)
          assertEquals(after, before)
          assert(!Files.exists(root.resolve(PlanTaskStorage.JournalPath)))
        }
      }
    }
  }

  test("intent IDs, text, dependencies and batch limits are validated before writes") {
    withRoot { (root, fs) =>
      run {
        for {
          storage <- PlanIntentStorage.make(fs, fs)
          _ <- ZIO.foreachDiscard(
            List(
              List(PlanIntentSimplifiedModel("I-99", "Unknown ID")),
              List(PlanIntentSimplifiedModel("../escape", "Not a path")),
              List(PlanIntentSimplifiedModel("", " ")),
              List(PlanIntentSimplifiedModel("", "x" * 8001)),
              List(PlanIntentSimplifiedModel("", "Wrong kind", dependsOn = List("I-1"))),
              List(PlanIntentSimplifiedModel("", "Duplicates", dependsOn = List("T-1", "T-1"))),
              List.fill(101)(PlanIntentSimplifiedModel("", "Too many"))
            )
          )(models => storage.set(models).either.map(result => assert(result.isLeft)))
        } yield assert(!Files.exists(root.resolve("sdlc")))
      }
    }
  }

  test("concurrent intent creates through one service retain unique durable IDs") {
    withRoot { (_, fs) =>
      run {
        for {
          service <- SdlcService.make(fs, fs)
          saved <- ZIO.foreachPar(1 to 20)(n => service.setIntents(List(PlanIntentSimplifiedModel("", s"Outcome $n"))))
          ids = saved.toList.flatten.map(_.id)
          reopened <- SdlcService.make(fs, fs)
          read <- reopened.readIntents(ids)
          full <- reopened.listIntents
          duplicate <- reopened.readIntents(List("I-1", "I-1")).either
          missing <- reopened.readIntents(List("I-999")).either
        } yield {
          assertEquals(ids.distinct.size, 20)
          assertEquals(read.map(_.id), ids)
          assertEquals(full.size, 20)
          assert(duplicate.isLeft)
          assert(missing.isLeft)
        }
      }
    }
  }

  test("intent tools use the shared service, reject injected metadata and preserve JSON shape") {
    withRoot { (_, fs) =>
      run {
        for {
          service <- SdlcService.make(fs, fs)
          tools = SdlcTools.make(service)
          set = tools.find(_.definition.key == ToolKey.SdlcSetIntents).get
          read = tools.find(_.definition.key == ToolKey.SdlcReadIntent).get
          created <- set.execute(
            ExternalToolCall(ToolKey.SdlcSetIntents, """{"intentsJson":[{"id":"","text":"Outcome"}]}""")
          )
          full <- service.listIntents
          loaded <- read.execute(ExternalToolCall(ToolKey.SdlcReadIntent, """{"intentIds":["I-1"]}"""))
          injected <- set
            .execute(
              ExternalToolCall(
                ToolKey.SdlcSetIntents,
                """{"intentsJson":[{"id":"I-1","text":"Forged","updatedAt":"2000-01-01T00:00:00Z"}]}"""
              )
            )
            .either
          wrongKey <- set.execute(ExternalToolCall(ToolKey.SdlcSet, """{"intentsJson":[]}""")).either
        } yield {
          assertEquals(created, loaded)
          assertEquals(created.text.fromJson[List[PlanIntentSimplifiedModel]], Right(full.map(_.toSimplifiedModel)))
          assert(injected.swap.toOption.get.isInstanceOf[YallmoError.InvalidToolArguments])
          assert(wrongKey.isLeft)
        }
      }
    }
  }

  test("corrupt and incomplete intent journals fail closed without resetting data") {
    withRoot { (_, fs) =>
      run {
        for {
          _ <- fs.createDirectories("sdlc")
          _ <- ZIO.foreachDiscard(List("not json\n", "\n", "[]\n", "[{}]\n", "[] ignored\n", "[]")) { contents =>
            for {
              _ <- fs.writeUtf8(PlanIntentStorage.JournalPath, contents, WriteMode.Replace)
              service <- SdlcService.make(fs, fs)
              listed <- service.listIntents.either
              saved <- service.setIntents(List(PlanIntentSimplifiedModel("", "Do not reset"))).either
              after <- fs.readUtf8(PlanIntentStorage.JournalPath)
            } yield {
              assert(listed.isLeft)
              assert(saved.isLeft)
              assertEquals(after, contents)
            }
          }
        } yield ()
      }
    }
  }

  test("intent replay preserves monotonic metadata and rejects changed creation timestamps") {
    withRoot { (_, fs) =>
      run {
        val future = Instant.parse("2100-01-01T00:00:00Z")
        val initial = PlanIntent("I-1", "Outcome", future, future)
        for {
          _ <- fs.createDirectories("sdlc")
          _ <- fs.writeUtf8(PlanIntentStorage.JournalPath, List(initial).toJson + "\n", WriteMode.Replace)
          service <- SdlcService.make(fs, fs)
          current <- service.readIntents(List("I-1"))
          _ <- service.setIntents(List(current.head.copy(text = "Updated")))
          full <- service.listIntents
          _ <- fs.writeUtf8(
            PlanIntentStorage.JournalPath,
            List(initial.copy(createdAt = future.plusSeconds(1), updatedAt = future.plusSeconds(1))).toJson + "\n",
            WriteMode.Append
          )
          invalid <- service.listIntents.either
        } yield {
          assertEquals(full.head.updatedAt, future)
          assertEquals(full.head.createdAt, future)
          assert(invalid.isLeft)
        }
      }
    }
  }

  private def withRoot(test: (Path, FileSystem) => Unit): Unit = {
    val root = Files.createTempDirectory("yallmo-sdlc-service-").toRealPath()
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
