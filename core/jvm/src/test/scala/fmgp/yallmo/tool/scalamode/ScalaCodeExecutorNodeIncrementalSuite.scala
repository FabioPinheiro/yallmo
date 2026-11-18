package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.tool.ScalaModeResult
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import munit.FunSuite
import zio.*

final class ScalaCodeExecutorNodeIncrementalSuite extends FunSuite {
  override val munitTimeout: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(5).minutes
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
  private def prerequisites(): Unit =
    assume(
      Seq("YALLMO_TEST_SCALA_CLI", "YALLMO_TEST_NODE", "YALLMO_TEST_NODE_ARTIFACT_ROOT").forall(sys.env.contains),
      "Set pinned CLI/Node/absolute reviewed artifact root to run incremental lab integration tests"
    )

  test("one retained linker handles identical and edited IR, failed builds, recovery and scope cleanup") {
    prerequisites()
    var directory: Option[Path] = None
    var process: Option[Process] = None
    run(ZIO.scoped {
      for {
        lab <- ScalaCodeExecutorNodeIncrementalLab.open(ScalaCodeExecutorNodeIncrementalLab.Mode.Linker)
        _ <- ZIO.succeed { directory = Some(lab.directory); process = lab.worker }
        a <- lab.execute("42")
        b <- lab.execute("42")
        c <- lab.execute("43")
        _ <- ZIO.succeed {
          assertEquals(List(a.output.trim, b.output.trim, c.output.trim), List("42", "42", "43"))
          assert(List(a, b, c).forall(r => r.exitCode == 0 && r.executionSetupTime.nonEmpty))
          List(a, b, c).zipWithIndex.foreach { case (result, index) =>
            assert(result.logs.exists(_.contains(s"links=${index + 1} ")), result.logs.mkString("\n"))
          }
        }
        concurrent <- lab.execute("45").zipPar(lab.execute("46"))
        _ <- ZIO.succeed {
          assertEquals(concurrent._1.output.trim, "45")
          assertEquals(concurrent._2.output.trim, "46")
        }
        bad <- lab.execute("doesNotExist")
        _ <- ZIO.succeed {
          assert(bad.result.isInstanceOf[ScalaModeResult.CompilationError], bad.output)
          assertEquals(bad.executionTime, 0L)
          assertEquals(bad.executionSetupTime, None)
          assert(!Files.exists(lab.directory.resolve("program.cjs")))
        }
        failedLink <- lab.link(
          "/missing-ir",
          java.lang.System.nanoTime() + 30.seconds.toNanos,
          new AtomicInteger(65536)
        )
        _ <- ZIO.succeed {
          assertNotEquals(failedLink.exitCode, 0)
          assert(!Files.exists(lab.directory.resolve("program.cjs")))
        }
        recovered <- lab.execute("44")
        _ <- ZIO.succeed { assertEquals(recovered.output.trim, "44"); assertEquals(recovered.exitCode, 0) }
        _ <- ZIO.foreachDiscard(
          List(
            "scala.scalajs.js.Dynamic.global.process.env",
            "scala.scalajs.js.Dynamic.global.fetch(\"https://example.com\")",
            "ZIO.attempt(java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\")))",
            "val previous = 5; previous"
          )
        ) { code =>
          lab.execute(code).map { result =>
            if code.startsWith("val previous") then assertEquals(result.output.trim, "5")
            else {
              assert(result.result.isInstanceOf[ScalaModeResult.CompilationError], result.output)
              assert(result.output.contains("safe"), result.output)
              assertEquals(result.executionTime, 0L)
            }
          }
        }
        noState <- lab.execute("previous")
        _ <- ZIO.succeed(assert(noState.result.isInstanceOf[ScalaModeResult.CompilationError]))
      } yield ()
    })
    assert(!Files.exists(directory.get))
    assert(!process.get.isAlive)
  }

  test("link deadline/cancellation terminate owned worker and remove its scoped workspace") {
    prerequisites()
    for cancel <- List(false, true) do {
      var directory: Option[Path] = None
      var process: Option[Process] = None
      run(ZIO.scoped {
        for {
          lab <- ScalaCodeExecutorNodeIncrementalLab.open(ScalaCodeExecutorNodeIncrementalLab.Mode.Linker)
          _ <- ZIO.succeed { directory = Some(lab.directory); process = lab.worker }
          // Stop only this owned worker, not shared Bloop. Its pending link cannot complete until killed.
          _ <- ZIO.attemptBlocking {
            val stopped = new ProcessBuilder("kill", "-STOP", lab.worker.get.pid().toString).start()
            assertEquals(stopped.waitFor(), 0)
          }
          _ <-
            if cancel then
              lab
                .link("/missing-ir", java.lang.System.nanoTime() + 30.seconds.toNanos, new AtomicInteger(65536))
                .forkScoped
                .flatMap(fiber => ZIO.sleep(100.millis) *> fiber.interrupt)
                .unit
            else
              lab
                .link("/missing-ir", java.lang.System.nanoTime() + 100.millis.toNanos, new AtomicInteger(65536))
                .map(result => assert(result.timedOut))
          _ <- ZIO.succeed {
            assert(!lab.worker.get.isAlive); assert(!Files.exists(lab.directory.resolve("program.cjs")))
          }
        } yield ()
      })
      assert(!Files.exists(directory.get))
      assert(!process.get.isAlive)
    }
  }
}
