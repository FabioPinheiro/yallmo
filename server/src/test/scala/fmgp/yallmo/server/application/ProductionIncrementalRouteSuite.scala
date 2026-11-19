package fmgp.yallmo.server.application

import fmgp.yallmo.tool.*
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import zio.*

final class ProductionIncrementalRouteSuite extends FunSuite {
  import ProductionIncrementalRoute.*
  override val munitTimeout = scala.concurrent.duration.Duration(240, "s")

  private def run[A](effect: Task[A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(effect.timeoutFail(new AssertionError("Production route test timed out"))(210.seconds))
      .getOrThrowFiberFailure()
  }
  private def live(): Unit = assume(
    sys.env.get("YALLMO_TEST_PRODUCTION_INCREMENTAL").contains("1"),
    "Set YALLMO_TEST_PRODUCTION_INCREMENTAL=1 with pinned offline caches and Node"
  )
  private def worker(output: ScalaModeOutput): ProcessHandle = {
    val pid = "pid=([0-9]+)".r.findFirstMatchIn(evidence(output)).get.group(1).toLong
    ProcessHandle.of(pid).orElseThrow()
  }
  private def directory(process: ProcessHandle): Path = Path.of(process.info().arguments().orElseThrow().last)
  private def waitFor(condition: => Boolean): Task[Unit] =
    ZIO
      .attemptBlockingInterrupt { while !condition do Thread.sleep(2) }
      .timeoutFail(new AssertionError("Condition not reached"))(30.seconds)

  test(
    "bundled page RPC retains linker for identical/changed IR, reconnect, errors and whole-source isolation; scope cleans worker/root"
  ) {
    live()
    var process: Option[ProcessHandle] = None
    var root: Option[Path] = None
    run(withServer { (_, rpc, _) =>
      for {
        firstConnection <- connection(rpc)
        first <- invoke(rpc, firstConnection, "val privatePrior = 41; privatePrior + 1")
        _ <- ZIO.attempt {
          process = Some(worker(first))
          val workspace = directory(process.get)
          root = Some(workspace.getParent)
          // Inspect the actual compiler-to-linker payload, not a test/application classloader's URLs.
          val inputs =
            Files.readString(workspace.resolve("classpath")).trim.split(java.io.File.pathSeparator).map(Path.of(_))
          assert(inputs.nonEmpty)
          inputs.foreach { input =>
            if Files.isDirectory(input) then assert(input.startsWith(workspace), input.toString)
            else
              scala.util.Using.resource(new java.util.jar.JarFile(input.toFile)) { jar =>
                val entries = jar.entries().asScala.filterNot(_.isDirectory).map(_.getName).toVector
                val scala3Shim = Set("scala3-library_sjs1_3-3.10.1.jar", "scala3-library_3-3.10.1.jar")
                  .contains(input.getFileName.toString) && entries == Vector("META-INF/MANIFEST.MF")
                // The pinned compiler also includes Scala's JVM signatures; the IR container ignores this jar.
                val scalaSignatures = input.getFileName.toString == "scala-library-3.10.1.jar"
                assert(entries.exists(_.endsWith(".sjsir")) || scala3Shim || scalaSignatures, inputs.mkString("\n"))
                val name = input.getFileName.toString
                if name.startsWith("yallmo-") then
                  assert(BundledJsArtifacts.artifactNames.map(Path.of(_).getFileName.toString).contains(name), name)
                assert(!name.contains("-tests"), name)
              }
          }
        }
        same <- invoke(rpc, firstConnection, "val privatePrior = 41; privatePrior + 1")
        _ <- rpc.disconnect(firstConnection)
        reconnected <- connection(rpc)
        changed <- invoke(rpc, reconnected, "43")
        concurrent <- invoke(rpc, reconnected, "53").zipPar(invoke(rpc, reconnected, "54"))
        missing <- invoke(rpc, reconnected, "privatePrior")
        noHello <- invoke(rpc, reconnected, "hello.helloWorld")
        noFs <- invoke(rpc, reconnected, "fs.list(\".\")")
        noHost <- invoke(rpc, reconnected, "java.lang.System.getenv()")
        runtimeError <- invoke(rpc, reconnected, "ZIO.fail(\"current failure\")")
        recovered <- invoke(rpc, reconnected, "44")
        _ <- rpc.disconnect(reconnected)
        _ <- ZIO.attempt {
          assertEquals(first.output.trim, "42")
          assertEquals(same.output.trim, "42")
          assertEquals(changed.output.trim, "43")
          assertEquals(concurrent._1.output.trim, "53")
          assertEquals(concurrent._2.output.trim, "54")
          assertEquals(identity(concurrent._1), identity(first))
          assertEquals(identity(concurrent._2), identity(first))
          assertEquals(recovered.output.trim, "44")
          List(same, changed, recovered).foreach(output => assertEquals(identity(output), identity(first)))
          assert(evidence(first).contains("links=1 "))
          assert(evidence(same).contains("links=2 "))
          val ir = "snippetIr=([a-f0-9]+)".r
          assertEquals(ir.findFirstIn(evidence(first)), ir.findFirstIn(evidence(same)))
          assertNotEquals(ir.findFirstIn(evidence(first)), ir.findFirstIn(evidence(changed)))
          List(first, same, changed, recovered).foreach { output =>
            assertEquals(output.exitCode, 0)
            assert(output.executionSetupTime.nonEmpty)
          }
          List(missing, noHello, noFs, noHost).foreach { output =>
            assert(output.result.isInstanceOf[ScalaModeResult.CompilationError])
            assertEquals(output.executionTime, 0L)
            assertEquals(output.executionSetupTime, None)
          }
          assert(runtimeError.result.isInstanceOf[ScalaModeResult.Executed] && runtimeError.exitCode != 0)
          assert(!recovered.output.contains("privatePrior") && !recovered.logs.mkString.contains("privatePrior"))
        }
      } yield ()
    })
    assert(!process.get.isAlive)
    assert(!Files.exists(root.get))
  }

  test(
    "one active/one waiter, queue deadline, bounded busy, runtime timeout and linker interruption invalidate without replay"
  ) {
    live()
    run(withServer { (_, rpc, manual) =>
      for {
        conn <- connection(rpc)
        initial <- invoke(rpc, conn, "42")
        initialProcess = worker(initial)
        workspace = directory(initialProcess)
        _ <- ZIO.attempt(
          Files.deleteIfExists(workspace.resolve(fmgp.yallmo.tool.scalamode.ScalaToolRuntime.ExecutionStartedFile))
        )
        executor = manual.executor.get
        active <- executor
          .execute("ZIO.succeed { var n = 0; while true do n += 1; n }", 3.seconds, 65536, Seq.empty)
          .fork
        _ <- waitFor(Files.exists(workspace.resolve(fmgp.yallmo.tool.scalamode.ScalaToolRuntime.ExecutionStartedFile)))
        queued <- executor.execute("52", 300.millis, 65536, Seq.empty).fork
        _ <- ZIO.sleep(50.millis)
        busy <- executor.execute("53", 1.second, 65536, Seq.empty)
        expired <- queued.join
        _ <- ZIO.attempt {
          assert(busy.result.isInstanceOf[ScalaModeResult.CompilationError] && busy.result.output.contains("busy"))
          assert(expired.result.isInstanceOf[ScalaModeResult.TimedOut])
          assert(expired.totalTime >= 250)
          assert(initialProcess.isAlive, "Queue timeout must not kill the active request's worker")
        }
        runtimeTimeout <- active.join
        _ <- ZIO.attempt {
          assert(runtimeTimeout.result.isInstanceOf[ScalaModeResult.TimedOut]); assert(!initialProcess.isAlive)
        }
        restarted <- invoke(rpc, conn, "55")
        restartedProcess = worker(restarted)
        _ <- ZIO.attempt {
          assertEquals(restarted.output.trim, "55"); assertNotEquals(restartedProcess.pid(), initialProcess.pid())
        }
        _ <- ZIO.attempt { restartedProcess.destroyForcibly(); () }
        _ <- waitFor(!restartedProcess.isAlive)
        cancelled <- executor.execute("56", 30.seconds, 65536, Seq.empty).fork
        newWorkspace <- ZIO
          .attemptBlockingInterrupt {
            var found: Option[Path] = None
            while found.isEmpty do {
              val paths = Files.list(workspace.getParent)
              try
                found = paths
                  .iterator()
                  .asScala
                  .find(p =>
                    p.getFileName.toString.startsWith("workspace-") &&
                      Files.exists(p.resolve("classpath")) && !Files.exists(p.resolve("response"))
                  )
              finally paths.close()
              if found.isEmpty then Thread.sleep(2)
            }
            found.get
          }
          .timeoutFail(new AssertionError("Link did not start"))(30.seconds)
        cancelledPid <- ZIO.attempt(
          "pid=([0-9]+)".r.findFirstMatchIn(Files.readString(newWorkspace.resolve("ready"))).get.group(1).toLong
        )
        _ <- cancelled.interrupt
        _ <- ZIO.attempt {
          assert(!ProcessHandle.of(cancelledPid).map[java.lang.Boolean](_.isAlive).orElse(false));
          assert(!Files.exists(newWorkspace))
        }
        finalOutput <- invoke(rpc, conn, "57")
        _ <- ZIO.attempt {
          assertEquals(finalOutput.output.trim, "57"); assert(evidence(finalOutput).contains("links=1 "))
        }
        _ <- rpc.disconnect(conn)
      } yield ()
    })
  }

  test("stalled production linker deadline and cancellation kill the worker and remove its workspace") {
    live()
    run(withServer { (_, rpc, manual) =>
      for {
        conn <- connection(rpc)
        _ <- ZIO.foreachDiscard(List(false, true)) { cancel =>
          for {
            initial <- invoke(rpc, conn, "81")
            process = worker(initial)
            workspace = directory(process)
            _ <- ZIO
              .scoped {
                for {
                  _ <- ZIO.attemptBlocking {
                    Files.delete(workspace.resolve("classpath"))
                    Files.delete(workspace.resolve("response"))
                    val stop = new ProcessBuilder("kill", "-STOP", process.pid().toString).start()
                    assertEquals(stop.waitFor(), 0)
                  }
                  pending <- manual.executor.get
                    .execute("82", if cancel then 30.seconds else 8.seconds, 65536, Seq.empty)
                    .forkScoped
                  _ <- waitFor(
                    Files.exists(workspace.resolve("classpath")) && !Files.exists(workspace.resolve("response"))
                  )
                  _ <-
                    if cancel then pending.interrupt.unit
                    else
                      pending.join.flatMap(output =>
                        ZIO.attempt {
                          assert(output.result.isInstanceOf[ScalaModeResult.TimedOut], output.toString)
                          assertEquals(output.executionTime, 0L)
                          assertEquals(output.executionSetupTime, None)
                        }
                      )
                  _ <- ZIO.attempt {
                    assert(!process.isAlive)
                    assert(!Files.exists(workspace))
                  }
                } yield ()
              }
              .ensuring(ZIO.attemptBlocking {
                if process.isAlive then { process.destroyForcibly(); () }
              }.orDie)
            recovered <- invoke(rpc, conn, "83")
            _ <- ZIO.attempt {
              assertEquals(recovered.output.trim, "83")
              assertNotEquals(worker(recovered).pid(), process.pid())
              assert(evidence(recovered).contains("links=1 "))
            }
          } yield ()
        }
        _ <- rpc.disconnect(conn)
      } yield ()
    })
  }

  test("link failure cannot run the prior JS; changed-source recovery and small shared output budget") {
    live()
    run(withServer { (_, rpc, manual) =>
      for {
        conn <- connection(rpc)
        initial <- invoke(rpc, conn, "61")
        workspace = directory(worker(initial))
        _ <- ZIO.attemptBlocking {
          // Force the real linker's publication to fail, independently of the snippet compiler's product repair.
          Files.delete(workspace.resolve("linked/program.cjs"))
          Files.createDirectory(workspace.resolve("linked/program.cjs"))
          Files.writeString(workspace.resolve("linked/program.cjs/blocker"), "blocked")
        }
        broken <- invoke(rpc, conn, "62")
        _ <- ZIO.attempt {
          assert(broken.result.isInstanceOf[ScalaModeResult.CompilationError], broken.toString)
          assertEquals(broken.executionTime, 0L)
          assertEquals(broken.executionSetupTime, None)
          assert(!Files.exists(workspace.resolve("program.cjs")))
        }
        _ <- ZIO.attempt {
          Files.deleteIfExists(workspace.resolve("linked/program.cjs/blocker"))
          Files.deleteIfExists(workspace.resolve("linked/program.cjs"))
        }
        recovered <- invoke(rpc, conn, "63")
        small <- manual.executor.get.execute("64", 30.seconds, 1024, Seq.empty)
        _ <- ZIO.attempt { assertEquals(recovered.output.trim, "63"); assertEquals(small.result.output.trim, "64") }
        _ <- rpc.disconnect(conn)
      } yield ()
    })
  }

  test("standalone shutdown stops real admitted Node work before removing worker and staged roots") {
    live()
    var ownedWorker: Option[ProcessHandle] = None
    var ownedNode: Option[ProcessHandle] = None
    var ownedRoot: Option[Path] = None
    var ownedApis: Option[Path] = None
    run(withServer { (_, rpc, _) =>
      for {
        conn <- connection(rpc)
        first <- invoke(rpc, conn, "71")
        process = worker(first)
        workspace = directory(process)
        _ <- ZIO.attempt {
          ownedWorker = Some(process)
          ownedRoot = Some(workspace.getParent)
          ownedApis = Files
            .readString(workspace.resolve("classpath"))
            .trim
            .split(java.io.File.pathSeparator)
            .map(Path.of(_))
            .find(_.getFileName.toString == "yallmo-scalacode-api_sjs1_3-2.0.0-SNAPSHOT.jar")
            .map(_.getParent.getParent)
          assert(ownedApis.exists(Files.isDirectory(_)))
          Files.deleteIfExists(workspace.resolve(fmgp.yallmo.tool.scalamode.ScalaToolRuntime.ExecutionStartedFile))
        }
        pending <- invoke(rpc, conn, "while true do ()").fork
        _ <- waitFor(Files.exists(workspace.resolve(fmgp.yallmo.tool.scalamode.ScalaToolRuntime.ExecutionStartedFile)))
        _ <- ZIO.attempt {
          val children = ProcessHandle.current().descendants()
          try
            ownedNode = children
              .iterator()
              .asScala
              .find(handle => handle.info().arguments().orElse(Array.empty[String]).contains("program.cjs"))
          finally children.close()
          assert(ownedNode.nonEmpty)
        }
        _ <- rpc.disconnect(conn)
        _ <- pending.interrupt // Disconnect/caller interruption abandons only the response wait.
        _ <- ZIO.attempt(assert(ownedNode.get.isAlive))
      } yield ()
    })
    assert(!ownedNode.get.isAlive)
    assert(!ownedWorker.get.isAlive)
    assert(!Files.exists(ownedRoot.get))
    assert(!Files.exists(ownedApis.get))
  }

  test("actual bundled page route fails closed when offline compiler metadata/dependencies are absent") {
    assume(sys.env.get("YALLMO_TEST_EXPECT_EMPTY_CACHE").contains("1"), "Run with an empty per-process COURSIER_CACHE")
    run(withServer { (_, rpc, _) =>
      for {
        conn <- connection(rpc)
        output <- invoke(rpc, conn, "42")
        _ <- ZIO.attempt {
          assert(output.result.isInstanceOf[ScalaModeResult.CompilationError], output.toString)
          assertEquals(output.executionTime, 0L)
          assertEquals(output.executionSetupTime, None)
          assert(output.output.contains("not found") || output.output.contains("Error downloading"))
        }
        _ <- rpc.disconnect(conn)
      } yield ()
    })
  }

  test("bounded edit cycles rotate worker/cache/workspace while returning newest values") {
    live()
    run(withServer { (_, rpc, _) =>
      for {
        conn <- connection(rpc)
        first <- invoke(rpc, conn, "0")
        old = worker(first)
        oldDirectory = directory(old)
        outputs <- ZIO.foreach(1 to 64)(n => invoke(rpc, conn, n.toString))
        _ <- ZIO.attempt {
          outputs.zipWithIndex.foreach { case (output, index) =>
            assertEquals(output.output.trim, (index + 1).toString)
          }
          assertEquals(identity(outputs(62)), identity(first))
          assertNotEquals(identity(outputs.last), identity(first))
          assert(evidence(outputs.last).contains("links=1 "))
          assert(!old.isAlive && !Files.exists(oldDirectory))
        }
        _ <- rpc.disconnect(conn)
      } yield ()
    })
  }
}
