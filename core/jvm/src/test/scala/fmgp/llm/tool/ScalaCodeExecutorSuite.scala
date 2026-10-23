package fmgp.yallmo.tool

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

  test("isolated temporary sources are removed after execution") {
    withFakeCli(
      "test \"$1\" = run && test \"$2\" = . || exit 8\ntest -f Snippet.scala && test -f Main.scala || exit 9\npwd\n"
    ) { executable =>
      val result = execute(executable)
      assertEquals(result.exitCode, 0)
      assert(!Files.exists(Path.of(result.output.trim)))
    }
  }

  test("output is drained but retained bytes are bounded") {
    withFakeCli("i=0; while [ $i -lt 10000 ]; do printf x; i=$((i+1)); done\nprintf error >&2\n") { executable =>
      val result = execute(executable, limit = 32)
      assertEquals(result.exitCode, 0)
      assertEquals(result.output, "x" * 32)
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
          .execute(fmgp.yallmo.orchestration.ExternalToolCall("scala", """{"code":"2 + 3"}"""))
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

  test("real CLI nightly resolution returns before the surrounding deadline") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run the nightly integration test")
    val result = run(
      new ScalaTool(timeout = 1.second)
        .execute(fmgp.yallmo.orchestration.ExternalToolCall("scala", """{"code":"2 + 3"}"""))
        .provide(ScalaCodeExecutor.layer(realCli.get, scalaVersion = "3.nightly", offline = false))
        .timeoutFail(new RuntimeException("Scala CLI resolution did not respect its deadline"))(5.seconds)
    )
    val json = ujson.read(result.text)
    if json("timedOut").bool then assert(json("hint").str.contains("deadline"))
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
      assert(rejected.output.contains("Compilation failed"), rejected.output)
    }
    val runtimeError = evaluate("1 / 0")
    assert(runtimeError.exitCode != 0 && runtimeError.output.contains("ArithmeticException"), runtimeError.output)
    assert(evaluate("while true do ()", 15.seconds).timedOut)
  }
}
