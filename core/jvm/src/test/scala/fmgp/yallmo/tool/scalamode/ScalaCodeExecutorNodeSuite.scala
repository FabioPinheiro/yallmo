package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.tool.ScalaModeResult
import java.nio.file.{Files, Path}
import munit.FunSuite
import zio.*

private[scalamode] object ScalaCodeExecutorNodeFixtures {
  def artifacts(root: Path): Seq[Path] = Seq("scalacode-api", "filesystem", "rpc", "model").map { name =>
    root.resolve(s"target/out/sjs1/scala-3.10.1/yallmo-$name/yallmo-${name}_sjs1_3-2.0.0-SNAPSHOT.jar")
  }
  def realArtifacts: Seq[Path] = artifacts(
    Path.of(sys.env.getOrElse("YALLMO_TEST_NODE_ARTIFACT_ROOT", ".")).toAbsolutePath
  )
  def realNode: String = sys.env.getOrElse("YALLMO_TEST_NODE", "node")
}

final class ScalaCodeExecutorNodeSuite extends FunSuite {
  override val munitTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(10).minutes
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private var scope: Scope.Closeable = scala.compiletime.uninitialized
  override def beforeEach(context: BeforeEach): Unit = { scope = run(Scope.make) }
  override def afterEach(context: AfterEach): Unit = run(scope.close(Exit.unit))
  private def provision(cli: String, node: String = ScalaCodeExecutorNodeFixtures.realNode): ScalaCodeExecutor =
    run(
      ScalaCodeExecutors
        .node(ScalaCodeExecutorNodeFixtures.realArtifacts, cli, node)
        .provideEnvironment(ZEnvironment[Scope](scope))
    )

  test("reviewed artifact contract rejects JVM, missing and wrong compiler inputs") {
    intercept[IllegalArgumentException](ScalamodeProgram.nodeClasspath(Seq(Path.of("application.jar")), "3.10.1"))
    intercept[IllegalArgumentException](ScalamodeProgram.nodeClasspath(Seq.empty, "3.10.0"))
    intercept[IllegalArgumentException](
      ScalamodeProgram.nodeClasspath(ScalaCodeExecutorNodeFixtures.artifacts(Path.of("missing")), "3.10.1")
    )
    val args = ScalamodeProgram.nodeCompileArguments("scala-cli", true, "reviewed")
    assert(args.contains("dev.zio::zio::2.1.27"))
    assert(!args.contains("dev.zio::zio:2.1.27"))
    assert(args.contains("--offline"))
    assert(!args.contains("--server=false"))
    assert(args.contains("--scalac-option=-language:experimental.captureChecking"))
    assert(args.contains("--scalac-option=-language:experimental.modularity"))
  }

  private def realExecutor: ScalaCodeExecutor = {
    assume(
      sys.env.contains("YALLMO_TEST_SCALA_CLI") && sys.env.contains("YALLMO_TEST_NODE"),
      "Set YALLMO_TEST_SCALA_CLI and YALLMO_TEST_NODE; package the four reviewed Scala.js artifacts first"
    )
    provision(sys.env("YALLMO_TEST_SCALA_CLI"))
  }

  test("real restricted Node values, lazy construction, effects, failures, marker and truncation") {
    val executor = realExecutor
    for (code, expected) <- List(
        "42" -> "42",
        "List(1,2,3).map(_ * 2).sum" -> "12",
        "ZIO.foreach(List(1,2,3))(n => ZIO.succeed(n * 2)).map(_.sum)" -> "12",
        "{ val unused = ZIO.succeed(1 / 0); ZIO.succeed(42) }" -> "42"
      )
    do {
      val result = run(executor.execute(code, 120.seconds, 16384))
      assertEquals(result.exitCode, 0, result.output)
      assertEquals(result.output.trim, expected)
      assert(result.executionSetupTime.nonEmpty, result.toString)
    }
    for code <- List(
        "ZIO.fail(\"expected failure\")",
        "ZIO.attempt(1 / 0)",
        "1 / 0",
        "ZIO.succeed(42).ensuring(ZIO.succeed(1 / 0))"
      )
    do {
      val result = run(executor.execute(code, 120.seconds, 16384))
      assert(result.result.isInstanceOf[ScalaModeResult.Executed], result.output)
      assertNotEquals(result.exitCode, 0)
      assert(result.executionSetupTime.nonEmpty)
      assert(result.output.contains("\"level\":\"ERROR\""), result.output)
    }
    val truncated = run(executor.execute("\"x\" * 100000", 120.seconds, 4096))
    assertEquals(truncated.exitCode, 0, truncated.output)
    assert(truncated.truncated)
    assert(truncated.executionSetupTime.nonEmpty)
  }

  test("real offline packaging fails closed with a fresh missing dependency/metadata cache") {
    val configured = realExecutor
    assert(run(configured.isAvailable), "Node prerequisites missing")
    val root = Files.createTempDirectory("yallmo-node-empty-cache-")
    try {
      val wrapper = root.resolve("cli")
      Files.writeString(
        wrapper,
        s"""#!/bin/sh
           |export COURSIER_CACHE="$root/cache"
           |exec "$$YALLMO_TEST_SCALA_CLI" "$$@"
           |""".stripMargin
      )
      assert(wrapper.toFile.setExecutable(true))
      val executor = provision(wrapper.toString)
      val result = run(executor.execute("42", 30.seconds, 16384))
      assert(result.result.isInstanceOf[ScalaModeResult.CompilationError], result.output)
      assertEquals(result.executionTime, 0L)
      assertEquals(result.executionSetupTime, None)
      assert(result.output.contains("Error downloading") || result.output.contains("not found"), result.output)
    } finally {
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => { Files.deleteIfExists(path); () })
      finally paths.close()
    }
  }

  test("real Node infinite evaluation is timed out after the observed marker") {
    val result = run(realExecutor.execute("while true do ()", 8.seconds, 16384))
    assert(result.result.isInstanceOf[ScalaModeResult.TimedOut], result.output)
    assert(result.executionSetupTime.nonEmpty, result.toString)
    assert(result.executionTime > 0L)
  }

  test("real Node cancellation after bootstrap kills the child and cleans its source directory") {
    val executor = realExecutor // Check opt-in prerequisites before acquiring test resources.
    assert(run(executor.isAvailable), "Node prerequisites missing")
    val root = Files.createTempDirectory("yallmo-node-cancel-")
    try {
      val wrapper = root.resolve("node")
      Files.writeString(
        wrapper,
        s"""#!/bin/sh
           |pwd > "$root/work"
           |echo $$$$ > "$root/pid"
           |exec "$$YALLMO_TEST_NODE" "$$@"
           |""".stripMargin
      )
      assert(wrapper.toFile.setExecutable(true))
      val child = provision(sys.env("YALLMO_TEST_SCALA_CLI"), wrapper.toString)
      run(ZIO.scoped {
        for {
          fiber <- child.execute("while true do ()", 120.seconds, 16384).forkScoped
          _ <- ZIO.attemptBlocking {
            val until = java.lang.System.nanoTime() + 30000000000L
            def started: Boolean = Files.exists(root.resolve("work")) &&
              Files.exists(
                Path.of(Files.readString(root.resolve("work")).trim).resolve(ScalaToolRuntime.ExecutionStartedFile)
              )
            while !started && java.lang.System.nanoTime() < until do Thread.sleep(10)
            assert(started, "Node never reached evaluation marker")
          }
          _ <- fiber.interrupt
        } yield ()
      })
      val pid = Files.readString(root.resolve("pid")).trim.toLong
      assert(!ProcessHandle.of(pid).isPresent || !ProcessHandle.of(pid).get().isAlive)
      assert(!Files.exists(Path.of(Files.readString(root.resolve("work")).trim)))
    } finally {
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => { Files.deleteIfExists(path); () })
      finally paths.close()
    }
  }

  test("real pinned safe JS rejects ambient filesystem/network/process and internal runtime authority") {
    val executor = realExecutor
    for code <- List(
        "ZIO.attempt(java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\")))",
        "scala.scalajs.js.Dynamic.global.process.env",
        "scala.scalajs.js.Dynamic.global.require(\"node:fs\")",
        "scala.scalajs.js.Dynamic.global.fetch(\"https://example.com\")",
        "scala.scalajs.js.Dynamic.newInstance(scala.scalajs.js.Dynamic.global.WebSocket)(\"ws://localhost\")",
        "scala.scalajs.js.eval(\"process.env\")",
        "scala.scalajs.js.Dynamic.global.Function(\"return process\")()",
        "zio.Runtime.default",
        "fmgp.yallmo.tool.scalamode.ScalaToolRuntime.bootstrap",
        "ScalaToolMain.run"
      )
    do {
      val result = run(executor.execute(code, 120.seconds, 16384))
      assert(result.result.isInstanceOf[ScalaModeResult.CompilationError], s"$code\n${result.output}")
      assert(result.output.contains("safe"), result.output)
      assertEquals(result.executionTime, 0L)
      assertEquals(result.executionSetupTime, None)
    }
  }
  test("production retained workspace rejects stale code, serializes calls and rotates after 64 compilations") {
    val executor = realExecutor
    val identities = (1 to 65).map { _ =>
      val result = run(executor.execute("42", 120.seconds, 16384))
      assertEquals(result.output.trim, "42", result.toString)
      val evidence = result.logs.find(_.startsWith("LINKER pid=")).getOrElse(fail(result.toString))
      evidence.takeWhile(_ != ' ') + evidence.split(" ")(1)
    }
    assertEquals(identities.take(64).distinct.size, 1)
    assertNotEquals(identities.head, identities.last)
    val bad = run(executor.execute("doesNotExist", 120.seconds, 16384))
    assert(bad.result.isInstanceOf[ScalaModeResult.CompilationError], bad.toString)
    assertEquals(bad.executionTime, 0L)
    val concurrent = run(executor.execute("45", 120.seconds, 16384).zipPar(executor.execute("46", 120.seconds, 16384)))
    assertEquals(concurrent._1.output.trim, "45")
    assertEquals(concurrent._2.output.trim, "46")
    assertEquals(run(executor.execute("val previous = 5; previous", 120.seconds, 16384)).output.trim, "5")
    assert(run(executor.execute("previous", 120.seconds, 16384)).result.isInstanceOf[ScalaModeResult.CompilationError])
  }

}
