package fmgp.yallmo.tool.sdlc

import fmgp.yallmo.fs.FileSystem
import fmgp.yallmo.model.{ToolKey, YallmoError}
import fmgp.yallmo.orchestration.{AgentTool, ExternalToolCall}
import fmgp.yallmo.tool.sdlc.llm.PlanTaskSimplifiedModel
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import munit.FunSuite
import zio.*
import zio.json.*

final class SdlcToolsSuite extends FunSuite {
  test("construction, empty reads and empty Set perform no writes") {
    withWorkspace { (root, fs) =>
      run {
        for {
          tools <- SdlcTools.make(fs, fs)
          read <- invoke(tools, ToolKey.SdlcReadTask, """{"taskIds":[]}""")
          saved <- invoke(tools, ToolKey.SdlcSet, """{"tasksJson":[]}""")
        } yield {
          assertEquals(read, "[]")
          assertEquals(saved, "[]")
          assert(!Files.exists(root.resolve(PlanTaskStorage.Directory)))
        }
      }
    }
  }

  test("Set allocates IDs, returns only simplified content, and persists a synced JSONL batch") {
    withWorkspace { (_, fs) =>
      run {
        for {
          tools <- SdlcTools.make(fs, fs)
          output <- invoke(
            tools,
            ToolKey.SdlcSet,
            """{"tasksJson":[{"id":"","title":"Investigate","description":"Authentication details"},{"id":"","title":"Check callers"}]}"""
          )
          tasks <- ZIO.fromEither(output.fromJson[List[PlanTaskSimplifiedModel]])
          history <- fs.read[List[PlanTask]](PlanTaskStorage.JournalPath).runCollect
          read <- invoke(tools, ToolKey.SdlcReadTask, """{"taskIds":["T-2","T-1"]}""")
        } yield {
          assertEquals(tasks.map(_.id.value), List("T-1", "T-2"))
          assert(!output.contains("createdAt"))
          assert(!output.contains("updatedAt"))
          assertEquals(history.size, 1)
          assertEquals(history.head.map(_.toSimplifiedModel), tasks)
          assert(history.head.forall(task => task.createdAt == task.updatedAt))
          assertEquals(read.fromJson[List[PlanTaskSimplifiedModel]], Right(tasks.reverse))
        }
      }
    }
  }

  test("updates preserve creation metadata, retain unmentioned tasks, and survive reopening") {
    withWorkspace { (_, fs) =>
      run {
        for {
          storage <- PlanTaskStorage.make(fs, fs)
          created <- storage.set(
            List(PlanTaskSimplifiedModel(PlanTaskId.New, "Parent"), PlanTaskSimplifiedModel(PlanTaskId.New, "Child"))
          )
          before <- fs.read[List[PlanTask]](PlanTaskStorage.JournalPath).runCollect
          changed = created.head.copy(
            state = TaskState.Doing,
            refs = List(PlanTaskRelationship.Subtask -> PlanTaskId.unsafe("T-2")),
            assumptions = List("Callers are internal"),
            archived = true
          )
          saved <- storage.set(List(changed))
          reopened <- PlanTaskStorage.make(fs, fs)
          read <- reopened.readTasks(List("T-2", "T-1"))
          history <- fs.read[List[PlanTask]](PlanTaskStorage.JournalPath).runCollect
        } yield {
          assertEquals(read, List(created(1), saved.head))
          assertEquals(saved.head.expectedRevision, 2L)
          assertEquals(history.size, 2)
          assertEquals(history.last.head.createdAt, before.head.head.createdAt)
          assert(!history.last.head.updatedAt.isBefore(before.head.head.updatedAt))
          assertEquals(history.last.head.id, before.head.head.id)
        }
      }
    }
  }

  test("replacement defaults clear planning fields, and identical existing-task retries do not append") {
    withWorkspace { (_, fs) =>
      run {
        for {
          tools <- SdlcTools.make(fs, fs)
          _ <- invoke(
            tools,
            ToolKey.SdlcSet,
            """{"tasksJson":[{"id":"","title":"Investigate","description":"Details to clear","state":"doing","assumptions":["Internal callers"],"archived":true,"steps":[{"done":false,"title":"Check"}]}]}"""
          )
          _ <- invoke(
            tools,
            ToolKey.SdlcSet,
            """{"tasksJson":[{"id":"T-1","title":"Investigate","expectedRevision":1}]}"""
          )
          beforeRetry <- fs.readUtf8(PlanTaskStorage.JournalPath)
          _ <- invoke(
            tools,
            ToolKey.SdlcSet,
            """{"tasksJson":[{"id":"T-1","title":"Investigate","expectedRevision":2}]}"""
          )
          afterRetry <- fs.readUtf8(PlanTaskStorage.JournalPath)
          read <- invoke(tools, ToolKey.SdlcReadTask, """{"taskIds":["T-1"]}""")
        } yield {
          assertEquals(
            read.fromJson[List[PlanTaskSimplifiedModel]],
            Right(List(PlanTaskSimplifiedModel(PlanTaskId.unsafe("T-1"), "Investigate", expectedRevision = 2L)))
          )
          assertEquals(afterRetry, beforeRetry)
        }
      }
    }
  }

  test("task steps flow through tools, revisions, search, checkpoint and reopen without changing task state") {
    withWorkspace { (_, fs) =>
      run {
        for {
          service <- SdlcService.make(fs, fs)
          tools = SdlcTools.make(service)
          output <- invoke(
            tools,
            ToolKey.SdlcSet,
            """{"tasksJson":[{"id":"","title":"Authentication","description":"Reliable renewal.","steps":[{"done":true,"title":"Reproduce"},{"done":false,"title":"Renew tokens","description":"Add **regression coverage**."}]}]}"""
          )
          created <- ZIO.fromEither(output.fromJson[List[PlanTaskSimplifiedModel]])
          read <- invoke(tools, ToolKey.SdlcReadTask, """{"taskIds":["T-1"]}""")
          updated = created.head.copy(steps = created.head.steps.map(_.copy(done = true)))
          saved <- invoke(tools, ToolKey.SdlcSet, s"""{"tasksJson":${List(updated).toJson}}""")
          beforeRetry <- fs.readUtf8(PlanTaskStorage.JournalPath)
          current <- ZIO.fromEither(saved.fromJson[List[PlanTaskSimplifiedModel]])
          _ <- service.setTasks(current)
          afterRetry <- fs.readUtf8(PlanTaskStorage.JournalPath)
          stale <- service.setTasks(created).either
          byTitle <- service.discover(query = "RENEW TOKENS")
          byDescription <- service.discover(query = "regression coverage")
          full <- service.listTasks
          _ <- service.checkpoint
          reopened <- SdlcService.make(fs, fs)
          loaded <- reopened.readTasks(List("T-1"))
          listed <- reopened.listTasks
        } yield {
          assertEquals(read, output)
          assertEquals(
            created.head.steps,
            List(
              PlanTaskStep(true, "Reproduce"),
              PlanTaskStep(false, "Renew tokens", "Add **regression coverage**.")
            )
          )
          assertEquals(current.head.expectedRevision, 2L)
          assertEquals(current.head.state, TaskState.Open)
          assertEquals(current.head.refs, Nil)
          assertEquals(beforeRetry, afterRetry)
          assert(stale.isLeft)
          assertEquals(byTitle.items.map(_.id), List("T-1"))
          assertEquals(byDescription.items.map(_.id), List("T-1"))
          assertEquals(loaded, current)
          assertEquals(listed, full)
          assertEquals(full.head.toMarkdown, current.head.toMarkdown)
        }
      }
    }
  }

  test("step schemas reject missing fields, wrong shapes and unknown properties before writes") {
    withWorkspace { (root, fs) =>
      run {
        for {
          tools <- SdlcTools.make(fs, fs)
          _ <- ZIO.foreachDiscard(
            List(
              """{"title":"Missing done"}""",
              """{"done":false}""",
              """{"done":"yes","title":"Wrong done"}""",
              """{"done":false,"title":42}""",
              """{"done":false,"title":"Wrong description","description":42}""",
              """{"done":false,"title":"Unknown field","extra":true}"""
            )
          ) { step =>
            invoke(tools, ToolKey.SdlcSet, s"""{"tasksJson":[{"id":"","title":"Task","steps":[$step]}]}""").either.map(
              result => assert(result.swap.toOption.get.isInstanceOf[YallmoError.InvalidToolArguments])
            )
          }
        } yield assert(!Files.exists(root.resolve(PlanTaskStorage.Directory)))
      }
    }
  }

  test("unknown and duplicate IDs fail before any mutation") {
    withWorkspace { (_, fs) =>
      run {
        for {
          storage <- PlanTaskStorage.make(fs, fs)
          created <- storage.set(List(PlanTaskSimplifiedModel(PlanTaskId.New, "Investigate")))
          before <- fs.readUtf8(PlanTaskStorage.JournalPath)
          _ <- ZIO.foreachDiscard(
            List(
              storage.set(List(PlanTaskSimplifiedModel(PlanTaskId.unsafe("T-2"), "Invented ID"))),
              storage.set(List(created.head, created.head)),
              storage.readTasks(List("T-1", "T-2")),
              storage.readTasks(List("T-1", "T-1")),
              storage.readTasks(List("../tasks.jsonl"))
            )
          )(effect => effect.either.map(result => assert(result.isLeft)))
          after <- fs.readUtf8(PlanTaskStorage.JournalPath)
        } yield assertEquals(after, before)
      }
    }
  }

  test("refs tuples round-trip through tools and permit distinct relationships to the same task") {
    withWorkspace { (_, fs) =>
      run {
        for {
          tools <- SdlcTools.make(fs, fs)
          output <- invoke(
            tools,
            ToolKey.SdlcSet,
            """{"tasksJson":[{"id":"","title":"Parent","refs":[["subtask","T-2"],["dependsOn","T-2"]]},{"id":"","title":"Child","refs":[["leftoverOf","T-1"]]}]}"""
          )
          saved <- ZIO.fromEither(output.fromJson[List[PlanTaskSimplifiedModel]])
          reopened <- SdlcTools.make(fs, fs)
          read <- invoke(reopened, ToolKey.SdlcReadTask, """{"taskIds":["T-1","T-2"]}""")
          before <- fs.readUtf8(PlanTaskStorage.JournalPath)
          _ <- ZIO.foreachDiscard(
            List(
              """[["unknown","T-2"]]""",
              """[["subtask"]]""",
              """[["subtask","T-2","extra"]]""",
              """[["subtask",42]]""",
              """[["subtask",""]]""",
              """[["subtask","T-2"],["subtask","T-2"]]"""
            )
          ) { refs =>
            invoke(
              tools,
              ToolKey.SdlcSet,
              s"""{"tasksJson":[{"id":"T-1","title":"Parent","expectedRevision":1,"refs":$refs}]}"""
            ).either.map(result => assert(result.isLeft, clues(refs)))
          }
          legacy <- invoke(
            tools,
            ToolKey.SdlcSet,
            """{"tasksJson":[{"id":"T-1","title":"Parent","expectedRevision":1,"subtasks":[]}]}"""
          ).either
          after <- fs.readUtf8(PlanTaskStorage.JournalPath)
        } yield {
          assertEquals(read, output)
          assertEquals(saved.head.refs.map(_._1), List(PlanTaskRelationship.Subtask, PlanTaskRelationship.DependsOn))
          assertEquals(before, after)
          assert(legacy.isLeft)
        }
      }
    }
  }

  test("missing references, self-links and dependency/containment cycles reject the entire batch") {
    withWorkspace { (_, fs) =>
      run {
        for {
          storage <- PlanTaskStorage.make(fs, fs)
          tasks <- storage.set(
            List(PlanTaskSimplifiedModel(PlanTaskId.New, "One"), PlanTaskSimplifiedModel(PlanTaskId.New, "Two"))
          )
          before <- fs.readUtf8(PlanTaskStorage.JournalPath)
          invalid = List(
            List(tasks.head.copy(refs = List(PlanTaskRelationship.DependsOn -> PlanTaskId.unsafe("T-3")))),
            List(tasks.head.copy(refs = List(PlanTaskRelationship.LeftoverOf -> PlanTaskId.unsafe("T-1")))),
            List(
              tasks.head.copy(refs =
                List(
                  PlanTaskRelationship.Subtask -> PlanTaskId.unsafe("T-2"),
                  PlanTaskRelationship.Subtask -> PlanTaskId.unsafe("T-2")
                )
              )
            ),
            List(
              tasks.head.copy(refs = List(PlanTaskRelationship.DependsOn -> PlanTaskId.unsafe("T-2"))),
              tasks(1).copy(refs = List(PlanTaskRelationship.DependsOn -> PlanTaskId.unsafe("T-1")))
            ),
            List(
              tasks.head.copy(refs = List(PlanTaskRelationship.Subtask -> PlanTaskId.unsafe("T-2"))),
              tasks(1).copy(refs = List(PlanTaskRelationship.Subtask -> PlanTaskId.unsafe("T-1")))
            )
          )
          _ <- ZIO.foreachDiscard(invalid)(batch => storage.set(batch).either.map(result => assert(result.isLeft)))
          after <- fs.readUtf8(PlanTaskStorage.JournalPath)
          _ <- storage.set(
            List(tasks.head.copy(refs = List(PlanTaskRelationship.DependsOn -> PlanTaskId.unsafe("T-2"))))
          )
        } yield assertEquals(after, before)
      }
    }
  }

  test("allocation revalidates self-links after assigning IDs") {
    withWorkspace { (root, fs) =>
      run {
        for {
          storage <- PlanTaskStorage.make(fs, fs)
          result <- storage
            .set(
              List(
                PlanTaskSimplifiedModel(
                  PlanTaskId.New,
                  "One",
                  refs = List(PlanTaskRelationship.LeftoverOf -> PlanTaskId.unsafe("T-1"))
                )
              )
            )
            .either
        } yield {
          assert(result.isLeft)
          assert(!Files.exists(root.resolve(PlanTaskStorage.JournalPath)))
        }
      }
    }
  }

  test("a batch may change both sides of a relationship without validating stale intermediate snapshots") {
    withWorkspace { (_, fs) =>
      run {
        for {
          storage <- PlanTaskStorage.make(fs, fs)
          tasks <- storage.set(
            List(PlanTaskSimplifiedModel(PlanTaskId.New, "One"), PlanTaskSimplifiedModel(PlanTaskId.New, "Two"))
          )
          first <- storage.set(
            List(tasks.head.copy(refs = List(PlanTaskRelationship.DependsOn -> PlanTaskId.unsafe("T-2"))))
          )
          reversed <- storage.set(
            List(
              first.head.copy(refs = Nil),
              tasks(1).copy(refs = List(PlanTaskRelationship.DependsOn -> PlanTaskId.unsafe("T-1")))
            )
          )
          reopened <- PlanTaskStorage.make(fs, fs)
          read <- reopened.readTasks(List("T-1", "T-2"))
        } yield assertEquals(read, reversed)
      }
    }
  }

  test("concurrent creates through one shared server-storage owner allocate unique durable IDs") {
    withWorkspace { (_, fs) =>
      run {
        for {
          storage <- PlanTaskStorage.make(fs, fs)
          saved <- ZIO.foreachPar(1 to 20)(n => storage.set(List(PlanTaskSimplifiedModel(PlanTaskId.New, s"Task $n"))))
          ids = saved.toList.flatten.map(_.id.value)
          reopened <- PlanTaskStorage.make(fs, fs)
          tasks <- reopened.readTasks(ids)
        } yield {
          assertEquals(ids.distinct.size, 20)
          assertEquals(tasks.map(_.id.value), ids)
          assertEquals(tasks.map(_.title).toSet, (1 to 20).map(n => s"Task $n").toSet)
        }
      }
    }
  }

  test("tools validate key, schema, nested metadata, argument types and input byte limits") {
    withWorkspace { (root, fs) =>
      run {
        for {
          tools <- SdlcTools.make(fs, fs)
          set = tools.find(_.definition.key == ToolKey.SdlcSet).get
          _ <- ZIO.foreachDiscard(
            List(
              "not json",
              "{}",
              """{"tasksJson":"[]"}""",
              """{"tasksJson":[{"id":"","title":42}]}""",
              """{"tasksJson":[{"id":"","text":"Removed field"}]}""",
              """{"tasksJson":[{"id":"","title":"Task","description":42}]}""",
              """{"tasksJson":[{"id":"","title":"Task","createdAt":"2026-01-01T00:00:00Z"}]}""",
              """{"tasksJson":[{"id":"","title":"Task","state":"blocked"}]}""",
              """{"tasksJson":[],"path":"../elsewhere.jsonl"}""",
              " " * (SdlcTools.MaxArgumentBytes + 1)
            )
          ) { arguments =>
            set.execute(ExternalToolCall(ToolKey.SdlcSet, arguments)).either.map { result =>
              assert(result.swap.toOption.get.isInstanceOf[YallmoError.InvalidToolArguments])
            }
          }
          wrongKey <- set.execute(ExternalToolCall(ToolKey.SdlcReadTask, """{"tasksJson":[]}""")).either
          wrongRead <- invoke(tools, ToolKey.SdlcReadTask, """{"taskIds":[42]}""").either
        } yield {
          assert(wrongKey.isLeft)
          assert(wrongRead.isLeft)
          assert(!Files.exists(root.resolve(PlanTaskStorage.Directory)))
        }
      }
    }
  }

  test("batch and content limits fail without creating a journal") {
    withWorkspace { (root, fs) =>
      run {
        for {
          storage <- PlanTaskStorage.make(fs, fs)
          _ <- ZIO.foreachDiscard(
            List(
              List.fill(PlanTaskStorage.MaxBatchSize + 1)(PlanTaskSimplifiedModel(PlanTaskId.New, "Task")),
              List(PlanTaskSimplifiedModel(PlanTaskId.New, " ")),
              List(PlanTaskSimplifiedModel(PlanTaskId.New, "x" * 161)),
              List(PlanTaskSimplifiedModel(PlanTaskId.New, "Two\nlines")),
              List(PlanTaskSimplifiedModel(PlanTaskId.New, "Two\rlines")),
              List(PlanTaskSimplifiedModel(PlanTaskId.New, "Task", description = "x" * 8001)),
              List(PlanTaskSimplifiedModel(PlanTaskId.New, "Task", assumptions = List(""))),
              List(
                PlanTaskSimplifiedModel(PlanTaskId.New, "Task", steps = List.fill(101)(PlanTaskStep(false, "Check")))
              ),
              List(PlanTaskSimplifiedModel(PlanTaskId.New, "Task", steps = List(PlanTaskStep(false, " ")))),
              List(PlanTaskSimplifiedModel(PlanTaskId.New, "Task", steps = List(PlanTaskStep(false, "x" * 161)))),
              List(PlanTaskSimplifiedModel(PlanTaskId.New, "Task", steps = List(PlanTaskStep(false, "Two\nlines")))),
              List(PlanTaskSimplifiedModel(PlanTaskId.New, "Task", steps = List(PlanTaskStep(false, "Two\rlines")))),
              List(
                PlanTaskSimplifiedModel(PlanTaskId.New, "Task", steps = List(PlanTaskStep(false, "Check", "x" * 2001)))
              )
            )
          )(batch => storage.set(batch).either.map(result => assert(result.isLeft)))
          read <- storage.readTasks((1 to 101).map(n => s"T-$n").toList).either
        } yield {
          assert(read.isLeft)
          assert(!Files.exists(root.resolve(PlanTaskStorage.Directory)))
        }
      }
    }
  }

  test("corrupt, blank, trailing-content and incomplete journals fail closed without rewriting") {
    withWorkspace { (_, fs) =>
      run {
        for {
          _ <- fs.createDirectories(PlanTaskStorage.Directory)
          _ <- ZIO.foreachDiscard(List("not json\n", "\n", "[] ignored\n", "[{}]\n", "[]", "[]\n")) { contents =>
            for {
              _ <- fs.writeUtf8(PlanTaskStorage.JournalPath, contents, fmgp.yallmo.fs.WriteMode.Replace)
              tools <- SdlcTools.make(fs, fs)
              read <- invoke(tools, ToolKey.SdlcReadTask, """{"taskIds":[]}""").either
              saved <- invoke(tools, ToolKey.SdlcSet, """{"tasksJson":[{"id":"","title":"Do not reset"}]}""").either
              after <- fs.readUtf8(PlanTaskStorage.JournalPath)
            } yield {
              assert(read.swap.toOption.get.isInstanceOf[YallmoError.ToolExecutionFailed])
              assert(saved.swap.toOption.get.isInstanceOf[YallmoError.ToolExecutionFailed])
              assertEquals(after, contents)
            }
          }
        } yield ()
      }
    }
  }

  test("oversize journals are bounded and never reset") {
    withWorkspace { (_, fs) =>
      run {
        for {
          _ <- fs.createDirectories(PlanTaskStorage.Directory)
          _ <- fs.writeUtf8(
            PlanTaskStorage.JournalPath,
            "x" * (PlanTaskStorage.MaxJournalBytes + 1),
            fmgp.yallmo.fs.WriteMode.Replace
          )
          storage <- PlanTaskStorage.make(fs, fs)
          result <- storage.readTasks(Nil).either
        } yield assert(result.isLeft)
      }
    }
  }

  test("replay rejects changed creation timestamps and accepts runtime updates despite clock rollback") {
    withWorkspace { (_, fs) =>
      run {
        val future = Instant.parse("2100-01-01T00:00:00Z")
        val initial = PlanTask(PlanTaskId.unsafe("T-1"), "Task", future, future)
        for {
          _ <- fs.createDirectories(PlanTaskStorage.Directory)
          _ <- fs.writeUtf8(PlanTaskStorage.JournalPath, List(initial).toJson + "\n", fmgp.yallmo.fs.WriteMode.Replace)
          storage <- PlanTaskStorage.make(fs, fs)
          current <- storage.readTasks(List("T-1"))
          _ <- storage.set(List(current.head.copy(title = "Updated")))
          good <- storage.readTasks(List("T-1"))
          _ <- fs.writeUtf8(
            PlanTaskStorage.JournalPath,
            List(initial.copy(createdAt = future.plusSeconds(1), updatedAt = future.plusSeconds(1))).toJson + "\n",
            fmgp.yallmo.fs.WriteMode.Append
          )
          bad <- storage.readTasks(List("T-1")).either
        } yield {
          assertEquals(good.head.title, "Updated")
          assert(bad.isLeft)
        }
      }
    }
  }

  test("filesystem failures remain tool execution errors, including symlinked journals") {
    withWorkspace { (root, fs) =>
      run {
        for {
          tools <- SdlcTools.make(fs, fs)
          _ <- ZIO.attempt(Files.writeString(root.resolve(PlanTaskStorage.Directory), "not a directory"))
          failed <- invoke(tools, ToolKey.SdlcSet, """{"tasksJson":[{"id":"","title":"Task"}]}""").either
          _ <- ZIO.attempt(Files.delete(root.resolve(PlanTaskStorage.Directory)))
          _ <- fs.createDirectories(PlanTaskStorage.Directory)
          _ <- ZIO.attempt(Files.createSymbolicLink(root.resolve(PlanTaskStorage.JournalPath), root.resolve("outside")))
          linked <- invoke(tools, ToolKey.SdlcSet, """{"tasksJson":[{"id":"","title":"Task"}]}""").either
        } yield {
          assert(failed.swap.toOption.get.isInstanceOf[YallmoError.ToolExecutionFailed])
          assert(linked.swap.toOption.get.isInstanceOf[YallmoError.ToolExecutionFailed])
          assert(!Files.exists(root.resolve("outside")))
        }
      }
    }
  }

  private def invoke(tools: Chunk[AgentTool[Any]], key: ToolKey, arguments: String): IO[YallmoError, String] =
    tools.find(_.definition.key == key).get.execute(ExternalToolCall(key, arguments)).map(_.text)

  private def withWorkspace(test: (Path, FileSystem) => Unit): Unit = {
    val root = Files.createTempDirectory("yallmo-sdlc-").toRealPath()
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
