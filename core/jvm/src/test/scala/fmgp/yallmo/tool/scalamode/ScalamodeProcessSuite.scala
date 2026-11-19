package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.tool.{CommandOutput, ScalaModeResult}
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import java.util.jar.{JarEntry, JarOutputStream}
import munit.FunSuite
import zio.*

/** Shared process checks migrated from the retired non-incremental Node engine. */
final class ScalamodeProcessSuite extends FunSuite {
  private def run[A](effect: Task[A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
  private def workspace(test: Path => Unit): Unit = {
    val root = Files.createTempDirectory("scalamode-process-test-")
    try test(root)
    finally {
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => { Files.deleteIfExists(path); () })
      finally paths.close()
    }
  }
  private def process(
      root: Path,
      script: String,
      budget: AtomicInteger,
      deadline: Long,
      setup: Option[AtomicLong] = None
  ): Task[CommandOutput] =
    ScalamodeProcess.runProcess(Seq("/bin/sh", "-c", script), root, deadline, budget, None, setup, nodeBackend = true)

  test("availability probes actual native WebSocket support instead of accepting version output") {
    workspace { root =>
      val artifacts = ScalaCodeExecutorNodeFixtures.artifacts(root)
      artifacts.foreach { path =>
        Files.createDirectories(path.getParent)
        val jar = new JarOutputStream(Files.newOutputStream(path))
        try { jar.putNextEntry(new JarEntry("Reviewed.sjsir")); jar.closeEntry() }
        finally jar.close()
      }
      def script(name: String, body: String): String = {
        val path = root.resolve(name)
        Files.writeString(path, "#!/bin/sh\n" + body)
        assert(path.toFile.setExecutable(true))
        path.toString
      }
      val cli = script("cli", "test \"$1\" = --version")
      val versionOnly = script("version-only", "test \"$1\" = --version")
      val native = script(
        "native",
        "test \"$1\" = -e && printf '%s' \"$2\" | grep -q 'typeof WebSocket' && printf '%s' \"$2\" | grep -q arraybuffer && printf '%s' \"$2\" | grep -q 'major<22'"
      )
      assert(!run(ScalaJsCompiler.probe(artifacts, cli, versionOnly)))
      assert(run(ScalaJsCompiler.probe(artifacts, cli, native)))
    }
  }

  test("one output budget spans compilation diagnostics, runtime stdout and file logs") {
    workspace { root =>
      val budget = new AtomicInteger(10)
      val deadline = java.lang.System.nanoTime() + 5.seconds.toNanos
      val compiled = run(process(root, "printf 1234 >&2", budget, deadline))
      val runtime = run(process(root, "printf 5678; printf abcdef > runtime.jsonl", budget, deadline))
      val logs = run(ScalamodeProcess.readRuntimeLogs(root, budget))
      assertEquals(compiled.logs, Vector("1234"))
      assertEquals(runtime.output, "5678")
      assertEquals(logs._1, Vector("ab"))
      assert(logs._2)
      assertEquals(budget.get(), 0)
    }
  }

  test("runtime markers are cleared before launch and stdout never implies setup completion") {
    workspace { root =>
      Files.createFile(root.resolve(ScalaToolRuntime.ExecutionStartedFile))
      val setup = new AtomicLong(-1)
      val result = run(
        process(
          root,
          "test ! -e execution-started || exit 12; printf execution-started; exit 2",
          new AtomicInteger(1024),
          java.lang.System.nanoTime() + 5.seconds.toNanos,
          Some(setup)
        )
      )
      assertEquals(result.exitCode, 2)
      assertEquals(setup.get(), -1L)
      val marked = run(
        process(
          root,
          "sleep 0.05; touch execution-started; sleep 0.1; printf 42",
          new AtomicInteger(1024),
          java.lang.System.nanoTime() + 5.seconds.toNanos,
          Some(setup)
        )
      )
      assertEquals(marked.output, "42")
      assert(setup.get() >= 30)
    }
  }

  test("a combined deadline is shared by compiler and runtime; link failures remain compilation errors") {
    workspace { root =>
      val budget = new AtomicInteger(1024)
      val deadline = java.lang.System.nanoTime() + 350.millis.toNanos
      assertEquals(run(process(root, "sleep 0.15", budget, deadline)).exitCode, 0)
      val result = run(process(root, "sleep 60", budget, deadline))
      assert(result.timedOut)
      val failure =
        run(process(root, "printf link-failed >&2; exit 1", budget, java.lang.System.nanoTime() + 5.seconds.toNanos))
      assert(ScalamodeProgram.result(failure, false).result.isInstanceOf[ScalaModeResult.CompilationError])
      assert(ScalamodeProgram.result(failure, true).result.isInstanceOf[ScalaModeResult.Executed])
    }
  }

  test("cancellation kills the owned child and its descendants before returning") {
    workspace { root =>
      run(ZIO.scoped {
        for {
          fiber <- process(
            root,
            "echo $$ > parent; sleep 60 & echo $! > child; wait",
            new AtomicInteger(1024),
            java.lang.System.nanoTime() + 30.seconds.toNanos
          ).forkScoped
          _ <- ZIO.attemptBlocking {
            val deadline = java.lang.System.nanoTime() + 5.seconds.toNanos
            while !Files.exists(root.resolve("child")) && java.lang.System.nanoTime() < deadline do Thread.sleep(10)
            assert(Files.exists(root.resolve("child")))
          }
          _ <- fiber.interrupt
          _ <- ZIO.attempt {
            for name <- List("parent", "child") do {
              val pid = Files.readString(root.resolve(name)).trim.toLong
              assert(!ProcessHandle.of(pid).isPresent || !ProcessHandle.of(pid).get().isAlive)
            }
          }
        } yield ()
      })
    }
  }

  test("processes strip JVM and Node injection variables and missing executables fail closed") {
    workspace { root =>
      val result = run(
        process(
          root,
          "test -z \"$NODE_OPTIONS$NODE_PATH$JAVA_TOOL_OPTIONS$JDK_JAVA_OPTIONS$_JAVA_OPTIONS$CLASSPATH\" || exit 88; printf 42",
          new AtomicInteger(1024),
          java.lang.System.nanoTime() + 5.seconds.toNanos
        )
      )
      assertEquals(result.output, "42")
      assert(
        run(
          ScalamodeProcess
            .runProcess(
              Seq("/missing-node"),
              root,
              java.lang.System.nanoTime() + 5.seconds.toNanos,
              new AtomicInteger(1024),
              None,
              nodeBackend = true
            )
            .either
        ).isLeft
      )
    }
  }

  test("unsupported platform dependency grants and custom JVM-only artifacts fail explicitly") {
    val dependencyError =
      intercept[UnsupportedOperationException](ScalamodeProgram.nodeDependencies(Seq("unreviewed:extra:1"), None))
    assert(dependencyError.getMessage.contains("Unsupported Scala.js dependency grant"))
    assertEquals(ScalamodeProgram.nodeDependencies(ScalaTool.DefaultDependencies, None), Seq("dev.zio::zio::2.1.27"))
    val binding = HelloWorldService.binding.copy(products = ScalamodeService.Products(jvm = Seq(getClass)))
    intercept[IllegalArgumentException](ScalamodeProgram.nodeServiceClasspath("reviewed", Some(binding)))
    val mapped = binding.copy(products =
      ScalamodeService.Products(scalaJsDependencies = Map("example::helper:1" -> "example::helper::1"))
    )
    assertEquals(ScalamodeProgram.nodeDependencies(Seq("example::helper:1"), Some(mapped)), Seq("example::helper::1"))
  }
}
