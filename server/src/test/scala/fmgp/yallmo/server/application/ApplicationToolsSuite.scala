package fmgp.yallmo.server.application

import fmgp.yallmo.orchestration.ExternalToolCall
import fmgp.yallmo.tool.*
import fmgp.yallmo.scalacode.{FileSystem as ScriptFileSystem}
import java.nio.file.Path
import munit.FunSuite
import zio.*

final class ApplicationToolsSuite extends FunSuite {
  private val workspace = Path.of("/explicit/application-workspace")
  private val allKeys = Chunk("readFile", "listFiles", "bash", "repositoryChanges", "scalamode", "scalaunsafe")

  test("startup selects only tools whose requirements pass without executing tools") {
    val (tools, calls) = run {
      for {
        services <- probes(git = false)
        tools <- ApplicationTools.evaluate(workspace).provideLayer(services.layer)
        calls <- services.calls.get
      } yield (tools, calls)
    }
    assertEquals(
      tools.available.map(_.definition.key),
      Chunk("readFile", "listFiles", "bash", "scalamode", "scalaunsafe")
    )
    assertEquals(
      tools.availability,
      ToolAvailability(
        Chunk("readFile", "listFiles", "bash", "scalamode", "scalaunsafe"),
        Chunk("repositoryChanges" -> Chunk("git is not available on PATH"))
      )
    )
    assertEquals(
      calls,
      Vector(s"directory:$workspace", "bash.available", "git.available", "scala.available", "dynamic.available")
    )
  }

  test("explicit workspace reaches directory and work-tree probes in startup order") {
    val otherWorkspace = Path.of("/another/application-workspace")
    val (first, second, calls) = run {
      for {
        services <- probes()
        first <- ApplicationTools.evaluate(workspace).provideLayer(services.layer)
        second <- ApplicationTools.evaluate(otherWorkspace).provideLayer(services.layer)
        calls <- services.calls.get
      } yield (first, second, calls)
    }
    assertEquals(first.available.map(_.definition.key), allKeys)
    assertEquals(first.availability, ToolAvailability(allKeys, Chunk.empty))
    assertEquals(second.availability, first.availability)
    assertEquals(
      calls,
      Vector(workspace, otherWorkspace).flatMap(path =>
        Vector(
          s"directory:$path",
          "bash.available",
          "git.available",
          s"git.worktree:$path",
          "scala.available",
          "dynamic.available"
        )
      )
    )
  }

  test("unavailable directory and executables retain ordered availability reasons") {
    val (tools, calls) = run {
      for {
        services <- probes(directory = false, bash = false, git = false, scala = false, dynamicScala = false)
        tools <- ApplicationTools.evaluate(workspace).provideLayer(services.layer)
        calls <- services.calls.get
      } yield (tools, calls)
    }
    assertEquals(tools.available, Chunk.empty)
    assertEquals(
      tools.availability,
      ToolAvailability(
        Chunk.empty,
        Chunk(
          "readFile" -> Chunk("workspace is not an accessible directory"),
          "listFiles" -> Chunk("workspace is not an accessible directory"),
          "bash" -> Chunk("bash is not available on PATH"),
          "repositoryChanges" -> Chunk("git is not available on PATH"),
          "scalamode" -> Chunk("Scala CLI (scala-cli) is not available on PATH"),
          "scalaunsafe" -> Chunk("Scala CLI (scala-cli) is not available on PATH")
        )
      )
    )
    assertEquals(
      calls,
      Vector(s"directory:$workspace", "bash.available", "git.available", "scala.available", "dynamic.available")
    )
  }

  test("installed git outside a work tree is omitted with its specific reason") {
    val tools = run {
      for {
        services <- probes(worktree = false)
        tools <- ApplicationTools.evaluate(workspace).provideLayer(services.layer)
      } yield tools
    }
    assertEquals(
      tools.available.map(_.definition.key),
      Chunk("readFile", "listFiles", "bash", "scalamode", "scalaunsafe")
    )
    assertEquals(
      tools.availability.unavailable,
      Chunk("repositoryChanges" -> Chunk("workspace is not a Git work tree"))
    )
  }

  test("constructed workspace tools use the supplied workspace with fake services only") {
    val calls = run {
      for {
        services <- probes(allowExecution = true)
        tools <- ApplicationTools.evaluate(workspace).provideLayer(services.layer)
        _ <- services.calls.set(Vector.empty)
        _ <- ZIO
          .foreachDiscard(
            Chunk(
              ExternalToolCall("readFile", """{"path":"notes.txt"}"""),
              ExternalToolCall("listFiles", """{"path":"src"}"""),
              ExternalToolCall("bash", """{"command":"echo fake"}"""),
              ExternalToolCall("repositoryChanges", """{"view":"status"}""")
            )
          )(call => tools.available.find(_.definition.key == call.toolKey).get.execute(call))
          .provideLayer(services.layer)
        calls <- services.calls.get
      } yield calls
    }
    assertEquals(
      calls,
      Vector(
        s"read:${workspace.resolve("notes.txt")}",
        s"list:${workspace.resolve("src")}",
        s"bash:$workspace",
        s"git.status:$workspace"
      )
    )
  }

  test("startup registers the restricted Scala tool with its executor") {
    val (definition, result, calls) = run {
      for {
        services <- probes(allowExecution = true)
        tools <- ApplicationTools.evaluate(workspace).provideLayer(services.layer)
        scala = tools.available.find(_.definition.key == "scalamode").get
        result <- scala
          .execute(ExternalToolCall("scalamode", """{"code":"(1 to 10).sum"}"""))
          .provideLayer(services.layer)
        calls <- services.calls.get
      } yield (scala.definition, result, calls)
    }
    assertEquals(definition, ScalaTool.definition)
    assertEquals(ujson.read(result.text)("output").str, "fake output")
    assert(calls.contains("scala:(1 to 10).sum"))
  }

  test("restricted and dynamic Scala availability are independent") {
    for (safe, dynamic) <- List((true, true), (true, false), (false, true), (false, false)) do {
      val tools = run {
        for {
          services <- probes(directory = false, bash = false, git = false, scala = safe, dynamicScala = dynamic)
          tools <- ApplicationTools.evaluate(workspace).provideLayer(services.layer)
        } yield tools
      }
      val expected =
        Chunk.fromIterable(List(Option.when(safe)("scalamode"), Option.when(dynamic)("scalaunsafe")).flatten)
      assertEquals(tools.available.map(_.definition.key), expected)
      assertEquals(tools.availability.unavailable.exists(_._1 == "scalamode"), !safe)
      assertEquals(tools.availability.unavailable.exists(_._1 == "scalaunsafe"), !dynamic)
    }
  }

  test("scalaunsafe advertises the API and adapts each invocation's existing filesystem") {
    val path = workspace.resolve("notes.txt")
    val code = s"ZIO.serviceWithZIO[fmgp.yallmo.scalacode.FileSystem](_.readUtf8(${ujson.write(path.toString)}))"
    def filesystem(content: String): WorkspaceFileSystem = new WorkspaceFileSystem {
      def isDirectory(path: Path): UIO[Boolean] = ZIO.succeed(true)
      def readUtf8(actual: Path): Task[String] = {
        assertEquals(actual, path)
        ZIO.succeed(content)
      }
      def list(path: Path): Task[Chunk[String]] = ZIO.dieMessage("Unexpected directory listing")
    }
    val executor = new ScalaCodeDynamicExecutor {
      def isAvailable: UIO[Boolean] = ZIO.succeed(true)
      def execute[R: EnvironmentTag](actual: String, timeout: Duration, maxOutputBytes: Int): RIO[R, CommandOutput] = {
        assertEquals(actual, code)
        assertEquals(timeout, 45.seconds)
        assertEquals(maxOutputBytes, 64 * 1024)
        ZIO.environmentWithZIO[R] { environment =>
          assertEquals(environment.getDynamic[WorkspaceFileSystem], None)
          for {
            service <- ZIO
              .fromOption(environment.getDynamic[ScriptFileSystem])
              .orElseFail(new IllegalStateException("Missing script-facing FileSystem"))
            content <- service.readUtf8(path.toString)
          } yield CommandOutput(0, false, content, false)
        }
      }
    }
    val (definition, outputs) = run {
      for {
        services <- probes()
        tools <- ApplicationTools.evaluate(workspace).provideLayer(services.layer)
        tool = tools.available.find(_.definition.key == "scalaunsafe").get
        outputs <- ZIO.foreach(List("first host instance", "second host instance")) { content =>
          tool
            .execute(ExternalToolCall("scalaunsafe", ujson.write(ujson.Obj("code" -> code))))
            .provideLayer(
              services.layer
                .map(_.add[WorkspaceFileSystem](filesystem(content)).add[ScalaCodeDynamicExecutor](executor))
            )
        }
      } yield (tool.definition, outputs)
    }
    assert(definition.description.contains("fmgp.yallmo.scalacode.FileSystem"))
    assert(definition.description.contains("def readUtf8(path: String)"))
    assert(definition.description.contains(workspace.toString))
    assert(definition.description.contains("not a sandbox"))
    assertEquals(
      outputs.map(result => ujson.read(result.text)("output").str),
      List("first host instance", "second host instance")
    )
  }

  test("real CLI scalaunsafe executes the registered application's filesystem API") {
    val executable = sys.env.get("YALLMO_TEST_SCALA_CLI")
    assume(executable.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run compilation integration tests")
    val path = workspace.resolve("notes.txt")
    val code = s"ZIO.serviceWithZIO[fmgp.yallmo.scalacode.FileSystem](_.readUtf8(${ujson.write(path.toString)}))"
    val (result, calls) = run {
      for {
        executor <- ZIO
          .service[ScalaCodeDynamicExecutor]
          .provide(
            ScalaCodeDynamicExecutor.layer(
              ScalaCodeDynamicExecutor.Config(executable = executable.get)
            )
          )
        services <- probes(allowExecution = true)
        tools <- ApplicationTools.evaluate(workspace).provideLayer(services.layer)
        tool = tools.available.find(_.definition.key == "scalaunsafe").get
        result <- tool
          .execute(ExternalToolCall("scalaunsafe", ujson.write(ujson.Obj("code" -> code))))
          .provideLayer(services.layer.map(_.add[ScalaCodeDynamicExecutor](executor)))
        calls <- services.calls.get
      } yield (result, calls)
    }
    val output = ujson.read(result.text)
    assertEquals(output("exitCode").num.toInt, 0, result.text)
    assertEquals(output("output").str, "fake output")
    assert(calls.contains(s"read:$path"))
  }

  private final case class Probes(calls: Ref[Vector[String]], layer: ULayer[ApplicationTools.Environment])

  private def probes(
      directory: Boolean = true,
      bash: Boolean = true,
      git: Boolean = true,
      worktree: Boolean = true,
      scala: Boolean = true,
      dynamicScala: Boolean = true,
      allowExecution: Boolean = false
  ): UIO[Probes] = Ref.make(Vector.empty[String]).map { calls =>
    def record(value: String): UIO[Unit] = calls.update(_ :+ value)
    def execution(value: String): UIO[CommandOutput] =
      if allowExecution then record(value).as(CommandOutput(0, false, "fake output", false))
      else ZIO.dieMessage(s"Tool discovery must not execute $value")

    val filesystem = new WorkspaceFileSystem {
      def isDirectory(path: Path): UIO[Boolean] = record(s"directory:$path").as(directory)
      def readUtf8(path: Path): Task[String] = execution(s"read:$path").map(_.output)
      def list(path: Path): Task[Chunk[String]] = execution(s"list:$path").as(Chunk("fake.txt"))
    }
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
          : Task[CommandOutput] = {
        val _ = (code, timeout, maxOutputBytes)
        execution(s"scala:$code")
      }
    }
    val dynamicCompiler = new ScalaCodeDynamicExecutor {
      def isAvailable: UIO[Boolean] = record("dynamic.available").as(dynamicScala)
      def execute[R: EnvironmentTag](code: String, timeout: Duration, maxOutputBytes: Int): RIO[R, CommandOutput] =
        execution(s"dynamic:$code $timeout $maxOutputBytes")
    }
    Probes(
      calls,
      ZLayer.succeed(filesystem) ++ ZLayer.succeed(shell) ++ ZLayer.succeed(repository) ++ ZLayer.succeed(compiler) ++
        ZLayer.succeed[ScalaCodeDynamicExecutor](dynamicCompiler)
    )
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
