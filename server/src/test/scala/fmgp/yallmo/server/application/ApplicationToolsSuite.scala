package fmgp.yallmo.server.application

import fmgp.yallmo.model.{ToolKey, ScalaModeResult}
import fmgp.yallmo.orchestration.{ExternalToolCall, ExternalToolResult}
import fmgp.yallmo.tool.*
import java.nio.file.{Files, Path}
import java.util.Comparator
import munit.FunSuite
import zio.*

final class ApplicationToolsSuite extends FunSuite {
  private val allKeys = Chunk(
    ToolKey.ReadFile,
    ToolKey.ListFiles,
    ToolKey.Bash,
    ToolKey.RepositoryChanges,
    ToolKey.Scalamode,
    ToolKey.SdlcSet,
    ToolKey.SdlcReadTask,
    ToolKey.SdlcSetIntents,
    ToolKey.SdlcReadIntent,
    ToolKey.SdlcList
  )

  test("startup selects only tools whose requirements pass without executing tools") {
    withWorkspace { workspace =>
      val (tools, calls) = run {
        for {
          services <- probes(git = false)
          tools <- ApplicationTools.evaluate(workspace, Some(workspace)).provideLayer(services.layer)
          calls <- services.calls.get
        } yield (tools, calls)
      }
      val expected = Chunk(
        ToolKey.ReadFile,
        ToolKey.ListFiles,
        ToolKey.Bash,
        ToolKey.Scalamode,
        ToolKey.SdlcSet,
        ToolKey.SdlcReadTask,
        ToolKey.SdlcSetIntents,
        ToolKey.SdlcReadIntent,
        ToolKey.SdlcList
      )
      assertEquals(tools.available.map(_.definition.key), expected)
      assertEquals(
        tools.availability,
        ToolAvailability(expected, Chunk(ToolKey.RepositoryChanges -> Chunk("git is not available on PATH")))
      )
      assertEquals(calls, Vector("bash.available", "git.available", "scala.available"))
      assert(
        !Files.exists(workspace.resolve(fmgp.yallmo.tool.sdlc.PlanTaskStorage.Directory)),
        "Startup must not create planning storage"
      )
    }
  }

  test("explicit workspaces reach work-tree probes in startup order") {
    withWorkspace { workspace =>
      withWorkspace { otherWorkspace =>
        val (first, second, calls) = run {
          for {
            services <- probes()
            first <- ApplicationTools.evaluate(workspace, Some(workspace)).provideLayer(services.layer)
            second <- ApplicationTools.evaluate(otherWorkspace, Some(otherWorkspace)).provideLayer(services.layer)
            calls <- services.calls.get
          } yield (first, second, calls)
        }
        assertEquals(first.availability, ToolAvailability(allKeys, Chunk.empty))
        assertEquals(second.availability, first.availability)
        assertEquals(
          calls,
          Vector(workspace, otherWorkspace).flatMap(path =>
            Vector("bash.available", "git.available", s"git.worktree:$path", "scala.available")
          )
        )
      }
    }
  }

  test("invalid roots disable only filesystem tools and retain ordered availability reasons") {
    withWorkspace { workspace =>
      Files.createSymbolicLink(workspace.resolve("link"), workspace)
      for invalid <- List(
          workspace.resolve("missing"),
          workspace.resolve("notes.txt"),
          workspace.resolve("link"),
          workspace.resolve("link/src")
        )
      do {
        val tools = run {
          for {
            services <- probes(bash = false, git = false, scala = false)
            tools <- ApplicationTools.evaluate(invalid, Some(workspace)).provideLayer(services.layer)
          } yield tools
        }
        assertEquals(
          tools.available.map(_.definition.key),
          Chunk(ToolKey.SdlcSet, ToolKey.SdlcReadTask, ToolKey.SdlcSetIntents, ToolKey.SdlcReadIntent, ToolKey.SdlcList)
        )
        assertEquals(
          tools.availability.unavailable,
          Chunk(
            ToolKey.ReadFile -> Chunk("workspace is not an accessible directory"),
            ToolKey.ListFiles -> Chunk("workspace is not an accessible directory"),
            ToolKey.Bash -> Chunk("bash is not available on PATH"),
            ToolKey.RepositoryChanges -> Chunk("git is not available on PATH"),
            ToolKey.Scalamode -> Chunk("Scala CLI (scala-cli) is not available on PATH")
          )
        )
      }
    }
  }

  test("installed git outside a work tree is omitted with its specific reason") {
    withWorkspace { workspace =>
      val tools = run {
        for {
          services <- probes(worktree = false)
          tools <- ApplicationTools.evaluate(workspace, Some(workspace)).provideLayer(services.layer)
        } yield tools
      }
      assertEquals(
        tools.available.map(_.definition.key),
        Chunk(
          ToolKey.ReadFile,
          ToolKey.ListFiles,
          ToolKey.Bash,
          ToolKey.Scalamode,
          ToolKey.SdlcSet,
          ToolKey.SdlcReadTask,
          ToolKey.SdlcSetIntents,
          ToolKey.SdlcReadIntent,
          ToolKey.SdlcList
        )
      )
      assertEquals(
        tools.availability.unavailable,
        Chunk(ToolKey.RepositoryChanges -> Chunk("workspace is not a Git work tree"))
      )
    }
  }

  test("constructed tools use their bound workspace and keep other service routing unchanged") {
    withWorkspace { workspace =>
      val (results, calls) = run {
        for {
          services <- probes(allowExecution = true)
          tools <- ApplicationTools.evaluate(workspace, Some(workspace)).provideLayer(services.layer)
          _ <- services.calls.set(Vector.empty)
          results <- ZIO
            .foreach(
              Chunk(
                ExternalToolCall(ToolKey.ReadFile, """{"path":"notes.txt"}"""),
                ExternalToolCall(ToolKey.ListFiles, """{"path":"src"}"""),
                ExternalToolCall(ToolKey.Bash, """{"command":"echo fake"}"""),
                ExternalToolCall(ToolKey.RepositoryChanges, """{"view":"status"}""")
              )
            )(call => tools.available.find(_.definition.key == call.toolKey).get.execute(call))
            .provideLayer(services.layer)
          calls <- services.calls.get
        } yield (results, calls)
      }
      assertEquals(results(0).text, "workspace content")
      assertEquals(results(1).text, "example.txt")
      assertEquals(calls, Vector(s"bash:$workspace", s"git.status:$workspace"))
    }
  }

  test("separate startup tool sets keep separate filesystem roots") {
    withWorkspace { first =>
      withWorkspace { second =>
        Files.writeString(second.resolve("notes.txt"), "second workspace")
        val results = run {
          for {
            services <- probes()
            one <- ApplicationTools.evaluate(first, Some(first)).provideLayer(services.layer)
            two <- ApplicationTools.evaluate(second, Some(second)).provideLayer(services.layer)
            results <- ZIO.foreach(Chunk(one, two)) { tools =>
              tools.available
                .find(_.definition.key == ToolKey.ReadFile)
                .get
                .execute(ExternalToolCall(ToolKey.ReadFile, """{"path":"notes.txt"}"""))
                .provideLayer(services.layer)
            }
          } yield results.map(_.text)
        }
        assertEquals(results, Chunk("workspace content", "second workspace"))
      }
    }
  }

  test("startup registers and executes restricted Scala") {
    withWorkspace { workspace =>
      val (definition, result, calls) = run {
        for {
          services <- probes(allowExecution = true)
          tools <- ApplicationTools.evaluate(workspace, Some(workspace)).provideLayer(services.layer)
          scala = tools.available.find(_.definition.key == ToolKey.Scalamode).get
          result <- scala
            .execute(ExternalToolCall(ToolKey.Scalamode, """{"code":"(1 to 10).sum"}"""))
            .provideLayer(services.layer)
          calls <- services.calls.get
        } yield (scala.definition, result, calls)
      }
      assertEquals(definition, ScalaTool.definition)
      assertEquals(result.text, "fake output")
      assert(calls.contains("scala:(1 to 10).sum"))
    }
  }

  test("SDLC tools are available without process dependencies and execute with their captured server storage") {
    withWorkspace { workspace =>
      val (created, read, calls) = run {
        for {
          services <- probes(bash = false, git = false, scala = false)
          tools <- ApplicationTools.evaluate(workspace, Some(workspace)).provideLayer(services.layer)
          created <- tools.available
            .find(_.definition.key == ToolKey.SdlcSet)
            .get
            .execute(ExternalToolCall(ToolKey.SdlcSet, """{"tasksJson":[{"id":"","text":"Plan the change"}]}"""))
            .provideLayer(services.layer)
          read <- tools.available
            .find(_.definition.key == ToolKey.SdlcReadTask)
            .get
            .execute(ExternalToolCall(ToolKey.SdlcReadTask, """{"taskIds":["T-1"]}"""))
            .provideLayer(services.layer)
          calls <- services.calls.get
        } yield (created, read, calls)
      }
      assertEquals(ujson.read(created.text)(0)("id").str, "T-1")
      assertEquals(read, created)
      assert(Files.exists(workspace.resolve(fmgp.yallmo.tool.sdlc.PlanTaskStorage.JournalPath)))
      assertEquals(calls, Vector("bash.available", "git.available", "scala.available"))
    }
  }

  test("separate server storage roots keep separate SDLC journals") {
    withWorkspace { first =>
      withWorkspace { second =>
        val (read, isolated) = run {
          for {
            services <- probes()
            one <- ApplicationTools.evaluate(first, Some(first)).provideLayer(services.layer)
            _ <- one.available
              .find(_.definition.key == ToolKey.SdlcSet)
              .get
              .execute(ExternalToolCall(ToolKey.SdlcSet, """{"tasksJson":[{"id":"","text":"First workspace"}]}"""))
              .provideLayer(services.layer)
            reopened <- ApplicationTools.evaluate(first, Some(first)).provideLayer(services.layer)
            two <- ApplicationTools.evaluate(second, Some(second)).provideLayer(services.layer)
            read <- reopened.available
              .find(_.definition.key == ToolKey.SdlcReadTask)
              .get
              .execute(ExternalToolCall(ToolKey.SdlcReadTask, """{"taskIds":["T-1"]}"""))
              .provideLayer(services.layer)
            isolated <- two.available
              .find(_.definition.key == ToolKey.SdlcReadTask)
              .get
              .execute(ExternalToolCall(ToolKey.SdlcReadTask, """{"taskIds":["T-1"]}"""))
              .provideLayer(services.layer)
              .either
          } yield (read, isolated)
        }
        assertEquals(ujson.read(read.text)(0)("text").str, "First workspace")
        assert(isolated.isLeft)
        assert(!Files.exists(second.resolve(fmgp.yallmo.tool.sdlc.PlanTaskStorage.Directory)))
      }
    }
  }

  test("SDLC follows shared server storage across workspace changes") {
    withWorkspace { storage =>
      withWorkspace { firstWorkspace =>
        withWorkspace { secondWorkspace =>
          val read = run {
            for {
              services <- probes()
              first <- ApplicationTools.evaluate(firstWorkspace, Some(storage)).provideLayer(services.layer)
              _ <- first.available
                .find(_.definition.key == ToolKey.SdlcSet)
                .get
                .execute(ExternalToolCall(ToolKey.SdlcSet, """{"tasksJson":[{"id":"","text":"Shared plan"}]}"""))
                .provideLayer(services.layer)
              // The earlier owner is no longer used; reopening uses the same journal with a different workspace.
              second <- ApplicationTools.evaluate(secondWorkspace, Some(storage)).provideLayer(services.layer)
              read <- second.available
                .find(_.definition.key == ToolKey.SdlcReadTask)
                .get
                .execute(ExternalToolCall(ToolKey.SdlcReadTask, """{"taskIds":["T-1"]}"""))
                .provideLayer(services.layer)
            } yield read
          }
          assertEquals(ujson.read(read.text)(0)("text").str, "Shared plan")
          assert(Files.exists(storage.resolve("sdlc/tasks.jsonl")))
          assert(!Files.exists(firstWorkspace.resolve("sdlc")))
          assert(!Files.exists(secondWorkspace.resolve("sdlc")))
        }
      }
    }
  }

  test("missing or inaccessible server storage disables only SDLC tools") {
    withWorkspace { workspace =>
      Files.createSymbolicLink(workspace.resolve("storage-link"), workspace)
      List(
        None,
        Some(workspace.resolve("missing")),
        Some(workspace.resolve("notes.txt")),
        Some(workspace.resolve("storage-link"))
      )
        .foreach { storageRoot =>
          val tools = run {
            for {
              services <- probes()
              tools <- ApplicationTools.evaluate(workspace, storageRoot).provideLayer(services.layer)
            } yield tools
          }
          assertEquals(
            tools.available.map(_.definition.key),
            allKeys.filterNot(key => fmgp.yallmo.tool.sdlc.SdlcTools.Definitions.exists(_.key == key))
          )
          assertEquals(
            tools.availability.unavailable.map(_._1),
            Chunk(
              ToolKey.SdlcSet,
              ToolKey.SdlcReadTask,
              ToolKey.SdlcSetIntents,
              ToolKey.SdlcReadIntent,
              ToolKey.SdlcList
            )
          )
        }
    }
  }

  private def withWorkspace(test: Path => Unit): Unit = {
    val root = Files.createTempDirectory("yallmo-startup-tools-").toRealPath()
    Files.writeString(root.resolve("notes.txt"), "workspace content")
    Files.createDirectory(root.resolve("src"))
    Files.createFile(root.resolve("src/example.txt"))
    try test(root)
    finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  private final case class Probes(calls: Ref[Vector[String]], layer: ULayer[ApplicationTools.Environment])

  private def probes(
      bash: Boolean = true,
      git: Boolean = true,
      worktree: Boolean = true,
      scala: Boolean = true,
      allowExecution: Boolean = false
  ): UIO[Probes] = Ref.make(Vector.empty[String]).map { calls =>
    def record(value: String): UIO[Unit] = calls.update(_ :+ value)
    def execution(value: String): UIO[CommandOutput] =
      if allowExecution then record(value).as(CommandOutput(0, false, "fake output", false))
      else ZIO.dieMessage(s"Tool discovery must not execute $value")

    val shell = new BashExecutor {
      def isAvailable: UIO[Boolean] = record("bash.available").as(bash)
      def execute(command: String, path: Path, timeout: Duration, maxOutputBytes: Int): Task[CommandOutput] = {
        val _ = (command, timeout, maxOutputBytes)
        execution(s"bash:$path")
      }
    }
    val repository = new GitClient {
      def isAvailable: UIO[Boolean] = record("git.available").as(git)
      def isWorkTree(path: Path): UIO[Boolean] = record(s"git.worktree:$path").as(worktree)
      def status(path: Path): IO[GitError, CommandOutput] = execution(s"git.status:$path")
      def unstagedDiff(path: Path): IO[GitError, CommandOutput] = execution(s"git.unstaged:$path")
      def stagedDiff(path: Path): IO[GitError, CommandOutput] = execution(s"git.staged:$path")
    }
    val compiler = new ScalaCodeExecutor {
      def isAvailable: UIO[Boolean] = record("scala.available").as(scala)
      def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String])
          : Task[ExternalToolResult.ScalaMode] = {
        val _ = (code, timeout, maxOutputBytes)
        execution(s"scala:$code").map(command => ExternalToolResult.ScalaMode(ScalaModeResult.Executed(command.output)))
      }
    }
    Probes(
      calls,
      ZLayer.succeed(shell) ++ ZLayer.succeed(repository) ++ ZLayer.succeed(compiler)
    )
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
