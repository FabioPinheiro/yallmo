package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.tool.ScalaModeResult
import java.nio.file.{Files, Path}
import java.util.jar.{JarEntry, JarOutputStream}
import munit.FunSuite
import zio.*

private[scalamode] object ScalaCodeExecutorNodeFixtures {
  def artifacts(root: Path): Seq[Path] = Seq("scalacode-api", "filesystem", "rpc", "model").map { name =>
    root.resolve(s"target/out/sjs1/scala-3.10.1/yallmo-$name/yallmo-${name}_sjs1_3-2.0.0-SNAPSHOT.jar")
  }
  def realBackend: ScalaCodeExecutorJVM.Backend = ScalaCodeExecutorJVM.Backend.Node(
    artifacts(Path.of(sys.env.getOrElse("YALLMO_TEST_NODE_ARTIFACT_ROOT", ".")).toAbsolutePath),
    sys.env.getOrElse("YALLMO_TEST_NODE", "node")
  )
}

final class ScalaCodeExecutorNodeSuite extends FunSuite {
  override val munitTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(10).minutes
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def withFakeBackend(compile: String, node: String)(test: (ScalaCodeExecutor, Path) => Unit): Unit = {
    val root = Files.createTempDirectory("yallmo-node-test-")
    try {
      val artifacts = ScalaCodeExecutorNodeFixtures.artifacts(root)
      artifacts.foreach { path =>
        Files.createDirectories(path.getParent)
        val jar = new JarOutputStream(Files.newOutputStream(path))
        try { jar.putNextEntry(new JarEntry("Reviewed.sjsir")); jar.closeEntry() }
        finally jar.close()
      }
      def script(name: String, body: String): Path = {
        val path = root.resolve(name)
        Files.writeString(path, "#!/bin/sh\n" + body + "\n")
        assert(path.toFile.setExecutable(true))
        path
      }
      val cli = script("cli", compile)
      val runtime = script("node", node)
      val executor = ScalaCodeExecutorJVM.make(
        cli.toString,
        backend = ScalaCodeExecutorJVM.Backend.Node(artifacts, runtime.toString)
      )
      test(executor, root)
    } finally {
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => { Files.deleteIfExists(path); () })
      finally paths.close()
    }
  }

  test("Node package/link is compilation, fresh direct runtime has no compiler or JVM flags") {
    withFakeBackend(
      "test \"$1\" = package || exit 11\nsleep 0.1\ntouch execution-started\nprintf compiler >&2",
      "test \"$1\" = --max-old-space-size=128 || exit 12\n" +
        "test \"$2\" = program.cjs || exit 13\ntest \"$3\" = --scalamode-node || exit 14\n" +
        "test ! -e execution-started || exit 15\nsleep 0.05\ntouch execution-started\nsleep 0.1\nprintf 42"
    ) { (executor, _) =>
      val result = run(executor.execute("42", 5.seconds, 1024))
      assertEquals(result.exitCode, 0, result.output)
      assertEquals(result.output, "42")
      assertEquals(result.logs, Seq("compiler"))
      assert(result.compilationTime >= 80)
      assert(result.executionTime >= 120)
      assert(result.executionSetupTime.exists(t => t >= 30 && t < result.executionTime))
      assert(result.totalTime >= result.compilationTime + result.executionTime)
    }
  }

  test("link failure is not runtime failure; absent marker never inferred from stdout") {
    withFakeBackend("printf link-failed >&2; exit 1", "exit 99") { (executor, _) =>
      val result = run(executor.execute("42", 5.seconds, 1024))
      assert(result.result.isInstanceOf[ScalaModeResult.CompilationError])
      assertEquals(result.executionTime, 0L)
      assertEquals(result.executionSetupTime, None)
    }
    withFakeBackend("touch execution-started", "printf execution-started; exit 2") { (executor, _) =>
      val result = run(executor.execute("42", 5.seconds, 1024))
      assert(result.result.isInstanceOf[ScalaModeResult.Executed])
      assertEquals(result.exitCode, 2)
      assertEquals(result.executionSetupTime, None)
    }
  }

  test("shared byte budget spans linker, runtime and file logs") {
    withFakeBackend("printf 1234 >&2", "printf 5678; printf abcdef > runtime.jsonl") { (executor, _) =>
      val result = run(executor.execute("42", 5.seconds, 10))
      assert(result.truncated)
      assertEquals(result.output, "5678")
      assertEquals(result.logs, Seq("1234", "a")) // Final display also charges the separator newline.
    }
  }

  test("shared deadline times out link or runtime and cleans working directory") {
    for duringLink <- List(true, false) do {
      withFakeBackend(
        "pwd > \"$(dirname \"$0\")/work\"\n" + (if duringLink then "sleep 60" else "sleep 0.15"),
        "touch execution-started; sleep 60"
      ) { (executor, root) =>
        val result = run(executor.execute("42", 400.millis, 1024))
        assert(result.result.isInstanceOf[ScalaModeResult.TimedOut])
        assert(!Files.exists(Path.of(Files.readString(root.resolve("work")).trim)))
        if duringLink then {
          assertEquals(result.executionTime, 0L)
          assertEquals(result.executionSetupTime, None)
        } else assert(result.executionSetupTime.nonEmpty)
      }
    }
  }

  test("cancellation kills Node and descendants before deleting invocation directory") {
    withFakeBackend(
      "exit 0",
      "pwd > \"$(dirname \"$0\")/work\"\nsleep 60 &\necho $! > \"$(dirname \"$0\")/pid\"\nwait"
    ) { (executor, root) =>
      run(ZIO.scoped {
        for {
          fiber <- executor.execute("42", 30.seconds, 1024).forkScoped
          _ <- ZIO.attemptBlocking {
            val until = java.lang.System.nanoTime() + 5000000000L
            while !Files.exists(root.resolve("pid")) && java.lang.System.nanoTime() < until do Thread.sleep(10)
            assert(Files.exists(root.resolve("pid")))
          }
          _ <- fiber.interrupt
        } yield ()
      })
      val pid = Files.readString(root.resolve("pid")).trim.toLong
      assert(!ProcessHandle.of(pid).isPresent || !ProcessHandle.of(pid).get().isAlive)
      assert(!Files.exists(Path.of(Files.readString(root.resolve("work")).trim)))
    }
  }

  test("services and extra dependency grants fail before metadata, listener or process acquisition") {
    val executor = ScalaCodeExecutorJVM.make(
      "must-not-launch",
      backend = ScalaCodeExecutorJVM.Backend.Node(Seq.empty, "must-not-launch")
    )
    val services = new ScalamodeService {
      def apiDocs: String = "must not be read"
      def binding: ScalamodeService.Binding = throw new AssertionError("metadata must not be read")
    }
    assert(run(executor.executeWithServices("42", services, 1.second, 1024).either).isLeft)
    val error = run(executor.execute("42", 1.second, 1024, Seq("unreviewed:extra:1")).either)
    assert(error.swap.toOption.get.getMessage.contains("extra dependency grants"))
    assert(!run(executor.isAvailable))
  }

  test("missing Node launch propagates a host failure and still deletes the invocation directory") {
    withFakeBackend("pwd > \"$(dirname \"$0\")/work\"\nrm \"$(dirname \"$0\")/node\"", "exit 99") { (executor, root) =>
      assert(run(executor.execute("42", 5.seconds, 1024).either).isLeft)
      assert(!Files.exists(Path.of(Files.readString(root.resolve("work")).trim)))
    }
  }

  test("compiler and Node strip preload, module-path and Java/classpath injection") {
    val check =
      "test -z \"$NODE_OPTIONS$NODE_PATH$JAVA_TOOL_OPTIONS$JDK_JAVA_OPTIONS$_JAVA_OPTIONS$CLASSPATH\" || exit 88\n"
    withFakeBackend(check + "exit 0", check + "printf 42") { (executor, _) =>
      val result = run(executor.execute("42", 5.seconds, 1024))
      assertEquals(result.exitCode, 0, result.output)
      assertEquals(result.output, "42")
    }
  }

  test("reviewed artifact contract rejects JVM, missing and wrong compiler inputs") {
    intercept[IllegalArgumentException](ScalaCodeExecutorJVM.nodeClasspath(Seq(Path.of("application.jar")), "3.10.1"))
    intercept[IllegalArgumentException](ScalaCodeExecutorJVM.nodeClasspath(Seq.empty, "3.10.0"))
    intercept[IllegalArgumentException](
      ScalaCodeExecutorJVM.nodeClasspath(ScalaCodeExecutorNodeFixtures.artifacts(Path.of("missing")), "3.10.1")
    )
    val args = ScalaCodeExecutorJVM.nodeCompileArguments("scala-cli", true, "reviewed")
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
    ScalaCodeExecutorJVM.make(sys.env("YALLMO_TEST_SCALA_CLI"), backend = ScalaCodeExecutorNodeFixtures.realBackend)
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
      val executor = ScalaCodeExecutorJVM.make(wrapper.toString, backend = ScalaCodeExecutorNodeFixtures.realBackend)
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
      val backend = ScalaCodeExecutorNodeFixtures.realBackend match {
        case ScalaCodeExecutorJVM.Backend.Node(artifacts, _) =>
          ScalaCodeExecutorJVM.Backend.Node(artifacts, wrapper.toString)
        case _ => fail("expected Node backend")
      }
      val child = ScalaCodeExecutorJVM.make(sys.env("YALLMO_TEST_SCALA_CLI"), backend = backend)
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
}
