package fmgp.yallmo.tool

import fmgp.yallmo.model.ToolKey
import fmgp.rpc.RpcWebSocket
import fmgp.yallmo.fs.{FsRead, FsError}
import java.nio.file.{Files, Path}
import munit.FunSuite
import zio.*

final class ScalaCodeExecutorSuite extends FunSuite {
  override val munitTimeout: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(5).minutes
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def withFakeCli(body: String)(test: Path => Unit): Unit = {
    val executable = Files.createTempFile("yallmo-fake-scala-", ".sh")
    try {
      Files.writeString(executable, "#!/bin/sh\n" + body)
      assert(executable.toFile.setExecutable(true))
      test(executable)
    } finally Files.deleteIfExists(executable)
  }

  private def execute(executable: Path, timeout: Duration = 10.seconds, limit: Int = 1024): CommandOutput =
    run(
      ZIO
        .serviceWithZIO[ScalaCodeExecutor](_.execute("42", timeout, limit))
        .provide(ScalaCodeExecutor.layer(executable.toString))
    )

  test("offline mode rejects a floating nightly before starting the CLI") {
    val result = run(
      ZIO
        .serviceWithZIO[ScalaCodeExecutor](_.execute("2 + 3", 1.second, 1024))
        .either
        .provide(ScalaCodeExecutor.layer("must-not-start", scalaVersion = "3.nightly", offline = true))
    )
    assert(result.left.toOption.exists(_.getMessage.contains("full nightly version")))
  }

  test("compiler classpath contains only the compiled API module") {
    val arguments = ScalaCodeExecutor.arguments()
    val classpath = arguments(arguments.indexOf("--classpath") + 1)
    val apiLocation =
      Path.of(classOf[fmgp.yallmo.scalacode.ScalaScript].getProtectionDomain.getCodeSource.getLocation.toURI)
    val coreLocation = Path.of(classOf[ScalaCodeExecutor].getProtectionDomain.getCodeSource.getLocation.toURI)
    assertEquals(classpath, apiLocation.toAbsolutePath.toString)
    assert(Files.exists(apiLocation))
    assertNotEquals(classpath, coreLocation.toAbsolutePath.toString)
    assert(ScalaCodeExecutor.snippet("api").contains("import fmgp.yallmo.scalacode.{ScalaToolApi as tool}"))
  }

  test("CLI arguments include pinned ZIO in both offline and online modes") {
    for offline <- List(true, false) do {
      val arguments = ScalaCodeExecutor.arguments(offline = offline)
      val dependencyIndex = arguments.indexOf("--dependency")
      assert(dependencyIndex >= 0)
      assertEquals(arguments(dependencyIndex + 1), "dev.zio::zio:2.1.27")
      assertEquals(arguments.contains("--offline"), offline)
    }
  }

  test("custom dependencies replace defaults and each gets a separate CLI flag") {
    val dependencies = Seq("dev.zio::zio-streams:2.1.27", "dev.zio::zio-json:1.1.1")
    val arguments = ScalaCodeExecutor.arguments(dependencies = dependencies)
    val actual = arguments.sliding(2).collect { case Seq("--dependency", dependency) => dependency }.toList
    assertEquals(actual, dependencies.toList)
    assert(!arguments.contains("dev.zio::zio:2.1.27"))
    assert(!ScalaCodeExecutor.arguments(dependencies = Seq.empty).contains("--dependency"))
  }

  test("ScalaTool forwards configured dependencies to the CLI, including an empty sequence") {
    withFakeCli("printf '%s\\n' \"$@\"\n") { executable =>
      for dependencies <- List(ScalaTool.DefaultDependencies, Seq("dev.zio::zio-json:1.1.1"), Seq.empty) do {
        val result = run(
          new ScalaTool(dependencies = dependencies)
            .execute(fmgp.yallmo.orchestration.ExternalToolCall(ToolKey.Scalamode, """{"code":"42"}"""))
            .provide(ScalaCodeExecutor.layer(executable.toString))
        )
        val arguments = ujson.read(result.text)("output").str.linesIterator.toList
        val actual = arguments.sliding(2).collect { case List("--dependency", dependency) => dependency }.toList
        assertEquals(actual, dependencies.toList)
      }
    }
  }

  test("isolated temporary sources are removed after execution") {
    withFakeCli(
      "test \"$1\" = run && test \"$2\" = . || exit 8\n" +
        "test -f Snippet.scala && test -f Main.scala && test ! -f ScalaToolApi.scala || exit 9\n" +
        "grep -q 'import fmgp.yallmo.scalacode.{ScalaToolApi as tool}' Snippet.scala || exit 10\n" +
        "grep -q 'import tool.\\*' Snippet.scala || exit 11\npwd\n"
    ) { executable =>
      val first = execute(executable)
      val second = execute(executable)
      for result <- List(first, second) do {
        assertEquals(result.exitCode, 0)
        assert(!Files.exists(Path.of(result.output.trim)))
      }
      assertNotEquals(first.output.trim, second.output.trim)
    }
  }

  test("stderr diagnostics are captured as logs without contaminating the value") {
    withFakeCli("printf '42\\n'; printf 'connected\\ndisconnected\\n' >&2\n") { executable =>
      val result = execute(executable)
      assertEquals(result.output, "42\n")
      assertEquals(result.logs, Seq("connected", "disconnected"))
      assert(!result.truncated)
    }
  }

  test("compiler stderr errors remain visible in the tool result") {
    withFakeCli("printf '[E006] Not Found Error: Snippet.scala:6:1\\nNot found: missing\\n' >&2\nexit 1\n") {
      executable =>
        val result = run(
          new ScalaTool()
            .execute(fmgp.yallmo.orchestration.ExternalToolCall(ToolKey.Scalamode, """{"code":"missing"}"""))
            .provide(ScalaCodeExecutor.layer(executable.toString))
        )
        val json = ujson.read(result.text)
        assertEquals(json("exitCode").num.toInt, 1)
        assert(json("output").str.contains("[E006] Not Found Error"))
        assert(json("output").str.contains("Not found: missing"))
        assert(result.logs.nonEmpty)
    }
  }

  test("stderr is drained with the same shared byte budget as the result") {
    withFakeCli("i=0; while [ $i -lt 10000 ]; do printf x >&2; i=$((i+1)); done\n") { executable =>
      val result = execute(executable, limit = 32)
      assertEquals(result.output, "")
      assertEquals(result.logs, Seq("x" * 32))
      assert(result.truncated)
    }
  }

  test("output is drained but retained bytes are bounded") {
    withFakeCli("i=0; while [ $i -lt 10000 ]; do printf x; i=$((i+1)); done\nprintf error >&2\n") { executable =>
      val result = execute(executable, limit = 32)
      assertEquals(result.exitCode, 0)
      assertEquals(result.output.length + result.logs.mkString.length, 32)
      assert(result.output.forall(_ == 'x'))
      assert(result.truncated)
    }
  }

  test("CLI exit does not wait for EOF from a descendant holding stdout open") {
    withFakeCli("sleep 10 &\necho $!\nexit 0\n") { executable =>
      val started = java.lang.System.nanoTime()
      val result = execute(executable)
      try {
        assertEquals(result.exitCode, 0)
        assert(!result.timedOut)
        assert(java.lang.System.nanoTime() - started < 2.seconds.toNanos, "output drain waited for inherited stdout")
      } finally {
        val child = ProcessHandle.of(result.output.trim.toLong)
        if child.isPresent then child.get().destroyForcibly()
      }
    }
  }

  test("tool returns a diagnostic before the outer agent deadline when CLI stalls") {
    withFakeCli("printf 'Checking compiler metadata\\n'; sleep 60\n") { executable =>
      val result = run(
        new ScalaTool(timeout = 300.millis)
          .execute(fmgp.yallmo.orchestration.ExternalToolCall(ToolKey.Scalamode, """{"code":"2 + 3"}"""))
          .provide(ScalaCodeExecutor.layer(executable.toString))
          .timeoutFail(new RuntimeException("tool did not return within the agent budget"))(3.seconds)
      )
      val json = ujson.read(result.text)
      assert(json("timedOut").bool)
      assert(json("output").str.contains("Checking compiler metadata"))
      assert(json("hint").str.contains("deadline"))
    }
  }

  test("timeout terminates the CLI and its child process") {
    withFakeCli("sleep 60 &\necho $!\nwait\n") { executable =>
      val result = execute(executable, timeout = 300.millis)
      assert(result.timedOut)
      assert(!isRunning(result.output.trim.toLong))
    }
  }

  private val unusedFilesystem = new FsRead {
    def readBytes(path: String): zio.stream.ZStream[Any, FsError, Byte] =
      zio.stream.ZStream.dieMessage("Unexpected read")
    def list(path: String): IO[FsError, Chunk[String]] = ZIO.dieMessage("Unexpected list")
  }

  test(
    "filesystem bootstrap rejects multiline, oversized UTF-8 and non-WebSocket endpoints before registration or launch"
  ) {
    val marker = Files.createTempFile("yallmo-bootstrap-launch-", ".txt")
    try
      withFakeCli(s"echo launched > '$marker'\nexit 9\n") { executable =>
        run(ZIO.scoped {
          for {
            route <- RpcWebSocket.route(auth = RpcWebSocket.AuthConfig(maxPendingTickets = 1))
            occupied <- route.register(FsReadRpc.contract.bind(unusedFilesystem))
            executor <- ZIO.service[ScalaCodeExecutor].provide(ScalaCodeExecutor.layer(executable.toString))
            _ <- ZIO.foreachDiscard(
              List(
                "ws://127.0.0.1/rpc\ninjected",
                "ws://127.0.0.1/rpc\rinjected",
                "ws://127.0.0.1/" + "x" * 2048,
                "ws://127.0.0.1/" + "é" * 1100,
                "http://127.0.0.1/rpc",
                "ws:///missing-host"
              )
            ) { endpoint =>
              executor
                .executeFilesystem("fs.list(\".\")", endpoint, 300.millis, 1024)
                .provideEnvironment(ZEnvironment[FsRead, RpcWebSocket.RpcRoute](unusedFilesystem, route))
                .either
                .map(result =>
                  assert(result.left.toOption.exists(_.isInstanceOf[IllegalArgumentException]), result.toString)
                )
            }
            _ <- route.revoke(occupied)
            _ <- ZIO.attempt(assertEquals(Files.size(marker), 0L, "Invalid endpoints must not launch the CLI"))
          } yield ()
        })
      }
    finally Files.deleteIfExists(marker)
  }

  test(
    "non-reading filesystem bootstrap obeys the deadline and cancellation, releasing process, sources and registration"
  ) {
    val marker = Files.createTempFile("yallmo-bootstrap-started-", ".txt")
    try
      withFakeCli(s"{ echo $$$$; pwd; } > '$marker'\npwd\nwhile :; do :; done\n") { executable =>
        run(ZIO.scoped {
          for {
            route <- RpcWebSocket.route(auth = RpcWebSocket.AuthConfig(maxPendingTickets = 1))
            executor <- ZIO.service[ScalaCodeExecutor].provide(ScalaCodeExecutor.layer(executable.toString))
            // Exercise the full accepted byte bound without relying on the child to consume any stdin.
            prefix = "ws://127.0.0.1/"
            endpoint = prefix + "x" * (2048 - prefix.length)
            execute = (timeout: Duration) =>
              executor
                .executeFilesystem("fs.list(\".\")", endpoint, timeout, 1024)
                .provideEnvironment(ZEnvironment[FsRead, RpcWebSocket.RpcRoute](unusedFilesystem, route))
            result <- execute(300.millis).timeoutFail(new Exception("bootstrap deadline was bypassed"))(10.seconds)
            _ <- ZIO.attempt {
              assert(result.timedOut)
              assert(!isRunning(Files.readString(marker).linesIterator.next().toLong))
              assert(!Files.exists(Path.of(result.output.trim)))
              Files.writeString(marker, "")
            }
            token <- route.register(FsReadRpc.contract.bind(unusedFilesystem))
            _ <- route.revoke(token)
            fiber <- execute(60.seconds).fork
            _ <- ZIO
              .attempt(Files.readString(marker))
              .repeat(Schedule.spaced(10.millis) && Schedule.recurWhile[String](_.linesIterator.size < 2))
              .timeoutFail(new Exception("non-reading child did not start"))(5.seconds)
              .onError(_ => fiber.interrupt.unit)
            exit <- fiber.interrupt.timeoutFail(new Exception("bootstrap cancellation did not finalize"))(10.seconds)
            _ <- ZIO.attempt {
              assert(exit.isInterrupted)
              val started = Files.readString(marker).linesIterator.toList
              assert(!isRunning(started.head.toLong))
              assert(!Files.exists(Path.of(started(1))))
            }
            next <- route.register(FsReadRpc.contract.bind(unusedFilesystem))
            _ <- route.revoke(next)
          } yield ()
        })
      }
    finally Files.deleteIfExists(marker)
  }

  private def isRunning(pid: Long): Boolean = {
    val process = ProcessHandle.of(pid)
    // ProcessHandle.isAlive may report true for a killed zombie awaiting reaping.
    process.isPresent && process.get().isAlive && process.get().info().command().isPresent
  }

  test("fiber cancellation tears down the process before cleaning temporary files") {
    val marker = Files.createTempFile("yallmo-scala-started-", ".txt")
    try
      withFakeCli(s"echo $$$$ > '$marker'\nwhile :; do :; done\n") { executable =>
        val (pid, result) = run {
          for {
            fiber <- ZIO
              .serviceWithZIO[ScalaCodeExecutor](_.execute("42", 60.seconds, 1024))
              .provide(ScalaCodeExecutor.layer(executable.toString))
              .fork
            started <- ZIO
              .attemptBlocking(Files.readString(marker).trim)
              .repeat(Schedule.spaced(10.millis) && Schedule.recurWhile[String](_.isEmpty))
              .timeoutFail(new RuntimeException("fake CLI did not start"))(5.seconds)
              .onError(_ => fiber.interrupt.unit)
            exit <- fiber.interrupt
          } yield (started._2.toLong, exit)
        }
        assert(result.isInterrupted)
        assert(!isRunning(pid))
      }
    finally Files.deleteIfExists(marker)
  }

  // Opt in with YALLMO_TEST_SCALA_CLI=/absolute/path/to/scala-cli; the pinned compiler must already be cached.
  private val realCli = sys.env.get("YALLMO_TEST_SCALA_CLI")
  private val realVersion = sys.env.getOrElse("YALLMO_TEST_SCALA_VERSION", ScalaCodeExecutor.ScalaVersion)
  private val realOffline = sys.env.getOrElse("YALLMO_TEST_SCALA_OFFLINE", "true") == "true"

  // Phase 1 only: compile against the actual filesystem artifact, but inject no host/RPC service.
  private def evaluateFsSnippet(code: String, label: String): CommandOutput = {
    assertEquals(ScalaTool.validate(code), Right(()))
    val directory = Files.createTempDirectory("yallmo-fs-feasibility-")
    val log = Path.of(s"/tmp/yallmo-phase1-$label.log")
    try {
      Files.writeString(
        directory.resolve("Snippet.scala"),
        "import scala.language.experimental.safe\n" +
          "import fmgp.yallmo.scalacode.{ScalaToolApi as tool}\n" +
          "import fmgp.yallmo.fs.{FsRead, FsError}\n" +
          "import zio.ZIO\nobject ScalaToolSnippet {\n  import tool.*\n" +
          "  def result(fs: FsRead)(using zio.Trace, zio.CanFail[FsError]): ZIO[Any, FsError, String] = {\n" +
          code + "\n  }\n}\n"
      )
      Files.writeString(directory.resolve("Main.scala"), fsFeasibilityMain)
      val arguments = ScalaCodeExecutor.arguments(
        realCli.get,
        ScalaCodeExecutor.ScalaVersion,
        offline = true,
        dependencies = Seq("dev.zio::zio-streams:2.1.27")
      )
      val filesystemClasspath =
        Path.of(classOf[fmgp.yallmo.fs.FsRead].getProtectionDomain.getCodeSource.getLocation.toURI).toAbsolutePath
      val classpathIndex = arguments.indexOf("--classpath") + 1
      val command = arguments.updated(
        classpathIndex,
        arguments(classpathIndex) + java.io.File.pathSeparator + filesystemClasspath
      )
      Files.writeString(Path.of(s"/tmp/yallmo-phase1-$label.command"), command.mkString("\n"))
      val builder = new ProcessBuilder(command*)
        .directory(directory.toFile)
        .redirectErrorStream(true)
        .redirectOutput(log.toFile)
      Seq("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH").foreach(builder.environment().remove)
      val process = builder.start()
      process.getOutputStream.close()
      val completed = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
      if !completed then {
        val children = process.descendants()
        try children.forEach(_.destroyForcibly())
        finally children.close()
        process.destroyForcibly()
        process.waitFor()
      }
      val input = Files.newInputStream(log)
      val output = try new String(input.readNBytes(64 * 1024), java.nio.charset.StandardCharsets.UTF_8)
      finally input.close()
      val truncated = Files.size(log) > 64 * 1024
      Files.writeString(log, output)
      CommandOutput(process.exitValue(), timedOut = !completed, output, truncated)
    } finally {
      val paths = Files.walk(directory)
      try {
        import scala.jdk.CollectionConverters.*
        paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists(_))
      } finally paths.close()
    }
  }

  // All capabilities and runtime execution stay local to this trusted main, never in the safe source.
  private val fsFeasibilityMain = """import fmgp.yallmo.fs.{FsRead, FsError}
    |import zio.*
    |import zio.stream.ZStream
    |object ScalaToolMain {
    |  def main(args: Array[String]): Unit = {
    |    var reads = List.empty[String]
    |    var lists = List.empty[String]
    |    var pulled = 0
    |    val fs: FsRead = new FsRead {
    |      def readBytes(path: String): ZStream[Any, FsError, Byte] = ZStream.unwrap(ZIO.succeed {
    |        reads = reads :+ path
    |        path match {
    |          case "hello.txt" => ZStream.fromIterable("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8).toSeq)
    |          case "bytes" => ZStream.repeatZIO(ZIO.succeed { pulled += 1; pulled.toByte })
    |          case _ => ZStream.fail(FsError.NotFound(path))
    |        }
    |      })
    |      def list(path: String): IO[FsError, Chunk[String]] = ZIO.succeed {
    |        lists = lists :+ path
    |        Chunk("bytes", "hello.txt")
    |      }
    |    }
    |    val effect: ZIO[Any, FsError, String] =
    |      ScalaToolSnippet.result(fs)(using Trace.empty, summon[CanFail[FsError]])
    |    Unsafe.unsafe { implicit unsafe =>
    |      println(Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())
    |    }
    |    println(s"reads=${reads.mkString(",")};lists=${lists.mkString(",")};pulled=$pulled")
    |  }
    |}
    |""".stripMargin

  test("phase 1 nightly executes injected FsRead effects with no ambient environment") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run filesystem feasibility")
    val result = evaluateFsSnippet(
      """for {
        |  text <- fs.readUtf8("hello.txt")
        |  entries <- fs.list(".")
        |  prefix <- fs.readBytes("bytes").take(3).runCollect.map(_.map(_.toInt).mkString(","))
        |  recovered <- fs.readUtf8("missing").fold((error: FsError) => "recovered:" + error.toString, identity)
        |} yield text + "|" + entries.mkString(",") + "|" + prefix + "|" + recovered
        |""".stripMargin,
      "effects"
    )
    assert(!result.timedOut && !result.truncated, result.output)
    assertEquals(result.exitCode, 0, result.output)
    assertEquals(
      result.output.linesIterator.toList,
      List("hello|bytes,hello.txt|1,2,3|recovered:NotFound(missing)", "reads=hello.txt,bytes,missing;lists=.;pulled=3")
    )
  }

  test("phase 1 nightly preserves pure expressions and API with the filesystem classpath") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run filesystem feasibility")
    val result = evaluateFsSnippet(
      "fs.readUtf8(\"hello.txt\").map(_ => { api; tool.api; List(1, 2, 3).map(_ * 2).sum.toString })",
      "pure-api"
    )
    assertEquals(result.exitCode, 0, result.output)
    assertEquals(
      result.output.linesIterator.toList,
      List("Yes Tool API is available", "Yes Tool API is available", "12", "reads=hello.txt;lists=;pulled=0")
    )
  }

  test("phase 1 nightly rejects authority expansion through the filesystem artifact and trusted main") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run filesystem feasibility")
    val forbidden = List(
      "open" -> "fmgp.yallmo.fs.FileSystem.open(\"/tmp\")",
      "constructor" -> "new fmgp.yallmo.fs.FileSystem(null)",
      "nio" -> "java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\"))",
      "network" -> "new java.net.Socket(\"example.com\", 80)",
      "runtime" -> "java.lang.Runtime.getRuntime.exec(\"true\")",
      "zio-runtime" -> "zio.Runtime.default",
      "unsafe-token" -> "zio.Unsafe",
      "main" -> "ScalaToolMain.main(Array.empty[String])",
      "main-fs" -> "ScalaToolMain.fs",
      "cast" -> "fs.asInstanceOf[fmgp.yallmo.fs.FsWrite]",
      "extractor" -> "fs.readUtf8(\"missing\").fold({ case FsError.NotFound(path) => path; case _ => \"other\" }, identity)"
    )
    for (label, code) <- forbidden do {
      val result = evaluateFsSnippet(s"val forbidden = { $code }; fs.readUtf8(\"hello.txt\")", s"reject-$label")
      assert(!result.timedOut && !result.truncated, s"$label: ${result.output}")
      assert(result.exitCode != 0 && result.output.contains("Compilation failed"), s"Accepted $code\n${result.output}")
      assert(
        result.output.contains("safe mode") || result.output.contains("from safe code") ||
          result.output.contains("cannot be accessed") ||
          result.output.contains("is not a member"),
        result.output
      )
    }
    assert(ScalaTool.validate("zio.Unsafe.unsafe { implicit u => () }").isLeft)
  }

  test("real CLI nightly resolution returns before the surrounding deadline") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run the nightly integration test")
    val result = run(
      new ScalaTool(timeout = 1.second)
        .execute(fmgp.yallmo.orchestration.ExternalToolCall(ToolKey.Scalamode, """{"code":"2 + 3"}"""))
        .provide(ScalaCodeExecutor.layer(realCli.get, scalaVersion = "3.nightly", offline = false))
        .timeoutFail(new RuntimeException("Scala CLI resolution did not respect its deadline"))(5.seconds)
    )
    val json = ujson.read(result.text)
    if json("timedOut").bool then assert(json("hint").str.contains("deadline"))
  }

  test("nightly returns E006 compilation diagnostics in the tool output") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run the compiler diagnostics integration test")
    val result = run(
      new ScalaTool(timeout = 120.seconds)
        .execute(fmgp.yallmo.orchestration.ExternalToolCall(ToolKey.Scalamode, """{"code":"doesNotExist"}"""))
        .provide(ScalaCodeExecutor.layer(realCli.get, realVersion, realOffline))
    )
    val json = ujson.read(result.text)
    assertEquals(json("exitCode").num.toInt, 1)
    assert(json("output").str.contains("[E006] Not Found Error"), result.text)
    assert(json("output").str.contains("Not found: doesNotExist"), result.text)
  }

  test("nightly provides the trusted runtime API on every fresh invocation") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run the runtime API integration test")
    for code <- List("api; tool.api; 42", "api") do {
      val result = run(
        ZIO
          .serviceWithZIO[ScalaCodeExecutor](_.execute(code, 120.seconds, 16 * 1024))
          .provide(ScalaCodeExecutor.layer(realCli.get, realVersion, realOffline))
      )
      assertEquals(result.exitCode, 0, result.output)
      assert(!result.logs.exists(_.startsWith("WARNING:")), result.logs.mkString("\n"))
      val lines = result.output.linesIterator.toList
      if code == "api" then {
        assertEquals(lines, List("()"))
        assertEquals(result.logs, Seq("Yes Tool API is available"))
      } else {
        assertEquals(lines, List("42"))
        assertEquals(result.logs, Seq("Yes Tool API is available", "Yes Tool API is available"))
      }
    }
  }

  test("nightly accepts pure code and rejects ambient JVM authority") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run the nightly integration test")
    def evaluate(code: String, timeout: Duration = 120.seconds): CommandOutput = run(
      ZIO
        .serviceWithZIO[ScalaCodeExecutor](_.execute(code, timeout, 16 * 1024))
        .provide(ScalaCodeExecutor.layer(realCli.get, realVersion, realOffline))
    )
    val small = evaluate("2 + 3")
    assertEquals(small.exitCode, 0, small.output)
    assert(small.output.linesIterator.contains("5"), small.output)
    val pure = evaluate("val values = List(1, 2, 3); values.map(_ * 2).sum")
    assertEquals(pure.exitCode, 0, pure.output)
    assert(pure.output.linesIterator.contains("12"), pure.output)

    for code <- List(
        "doesNotExist",
        "new fmgp.yallmo.tool.ScalaTool()",
        "java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\"))",
        "java.nio.file.Files.writeString(java.nio.file.Path.of(\"forbidden.txt\"), \"no\")",
        "java.lang.System.getenv()",
        "java.lang.System.exit(0)",
        "new java.lang.Thread()",
        "scala.io.Source.fromFile(\"/etc/passwd\").mkString",
        "sys.env",
        "sys.props",
        "sys.runtime",
        "java.lang.Runtime.getRuntime.exec(\"true\")",
        "new java.net.Socket(\"example.com\", 80)",
        "classOf[String].getDeclaredMethods",
        "1.asInstanceOf[String]",
        "println(42)"
      )
    do {
      val rejected = evaluate(code)
      assert(!rejected.timedOut && rejected.exitCode != 0, s"Unexpectedly accepted: $code\n${rejected.output}")
      assertEquals(rejected.output, "")
      assert(rejected.logs.exists(_.contains("Compilation failed")), rejected.logs.mkString("\n"))
    }
    val runtimeError = evaluate("1 / 0")
    assert(
      runtimeError.exitCode != 0 && runtimeError.logs.exists(_.contains("ArithmeticException")),
      runtimeError.logs.mkString("\n")
    )
    assert(evaluate("while true do ()", 15.seconds).timedOut)
  }
}
