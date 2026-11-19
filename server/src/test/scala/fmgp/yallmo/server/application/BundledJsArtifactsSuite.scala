package fmgp.yallmo.server.application

import fmgp.yallmo.tool.scalamode.ScalaCodeExecutors
import java.io.{ByteArrayInputStream, IOException, InputStream, UncheckedIOException}
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.concurrent.{CopyOnWriteArrayList, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.jar.{JarFile, JarInputStream}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scala.util.Using
import zio.*

final class BundledJsArtifactsSuite extends FunSuite {
  private def run[A](effect: Task[A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
  }

  private def roots: Set[Path] = Using.resource(Files.list(Path.of(java.lang.System.getProperty("java.io.tmpdir")))) {
    _.iterator().asScala.filter(_.getFileName.toString.startsWith("yallmo-scalamode-js-")).toSet
  }

  private def removeTestRoot(root: Path): Unit =
    Using.resource(Files.walk(root))(_.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists(_)))

  private def checkContents(root: Path, open: String => InputStream): Unit = {
    val files = Using.resource(Files.walk(root))(_.iterator().asScala.filter(Files.isRegularFile(_)).toList)
    assertEquals(files.map(root.relativize(_).toString).toSet, BundledJsArtifacts.artifactNames.toSet)
    assertEquals(files.size, 4)
    BundledJsArtifacts.resourceNames.zip(BundledJsArtifacts.artifactNames).foreach { case (resource, artifact) =>
      val expected = Using.resource(open(resource))(_.readAllBytes())
      assert(java.util.Arrays.equals(Files.readAllBytes(root.resolve(artifact)), expected))
      Using.resource(new JarFile(root.resolve(artifact).toFile)) { jar =>
        assert(jar.entries().asScala.exists(_.getName.endsWith(".sjsir")))
      }
    }
  }

  private def checkTooling(open: String => InputStream): Unit = {
    val names = Using.resource(open("scalamode-linker/classpath.txt"))(input =>
      new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).linesIterator.toVector
    )
    assertEquals(
      names.map(_.drop(5)).toSet,
      Set(
        "yallmo-linker-worker_3-2.0.0-SNAPSHOT.jar",
        "scala3-library_3-3.10.1.jar",
        "scala-library-3.10.1.jar",
        "scalajs-linker_3-1.22.0.jar",
        "scalajs-linker-interface_3-1.22.0.jar",
        "scalajs-ir_3-1.22.0.jar",
        "closure-compiler-v20220202.jar",
        "scala-parallel-collections_3-1.2.0.jar",
        "scalajs-logging_3-1.2.0.jar"
      )
    )
    names.foreach { name =>
      Using.resource(new JarInputStream(open(s"scalamode-linker/$name"))) { jar =>
        val classes = Iterator
          .continually(jar.getNextJarEntry)
          .takeWhile(_ != null)
          .map(_.getName)
          .filterNot(_.endsWith("/"))
          .toVector
        // Scala 3.10's compatibility shim contains only its manifest; definitions live in scala-library.
        assert(classes.exists(_.endsWith(".class")) || name.drop(5) == "scala3-library_3-3.10.1.jar", name)
        val applicationClasses = classes.filter(_.startsWith("fmgp/"))
        assert(applicationClasses.forall(_.startsWith("fmgp/yallmo/linker/")), name)
        if name.contains("yallmo-linker-worker") then
          assert(classes.contains("fmgp/yallmo/linker/IncrementalLinkerWorker.class"))
      }
    }
  }

  test("classpath resources stage exactly four JS IR products, remain live, and use distinct lifetime roots") {
    val first = run(ZIO.scoped {
      for {
        root <- BundledJsArtifacts.stage()
        _ <- ZIO.attempt(checkContents(root, getClass.getClassLoader.getResourceAsStream))
        // Registered after staging, just like application-owned work: shutdown still sees all resources.
        _ <- ZIO.addFinalizer(ZIO.succeed(assert(Files.exists(root.resolve(BundledJsArtifacts.artifactNames.head)))))
      } yield root
    })
    assert(!Files.exists(first))
    val second = run(ZIO.scoped(BundledJsArtifacts.stage().tap(root => ZIO.succeed(assert(Files.exists(root))))))
    assertNotEquals(first, second)
    assert(!Files.exists(second))
  }

  test(
    "actual packaged server JAR contains only the four allowlisted resources and stages without a parent classpath"
  ) {
    assume(sys.env.contains("YALLMO_TEST_SERVER_JAR"), "Set YALLMO_TEST_SERVER_JAR to server / Compile / packageBin")
    val file = Path.of(sys.env("YALLMO_TEST_SERVER_JAR")).toFile
    Using.resource(new JarFile(file)) { jar =>
      val entries =
        jar.entries().asScala.filterNot(_.isDirectory).map(_.getName).filter(_.startsWith("scalamode-js/")).toSet
      assertEquals(entries, BundledJsArtifacts.resourceNames.toSet)
    }
    Using.resource(new URLClassLoader(Array(file.toURI.toURL), null)) { loader =>
      checkTooling(loader.getResourceAsStream)
      val root = run(ZIO.scoped {
        BundledJsArtifacts
          .stage(loader.getResourceAsStream)
          .tap(root => ZIO.attempt(checkContents(root, loader.getResourceAsStream)))
      })
      assert(!Files.exists(root))
    }
  }

  test("production linker tooling is an explicit isolated nested runtime, and missing tooling fails closed") {
    val loader = getClass.getClassLoader
    checkTooling(loader.getResourceAsStream)
    val names = Using.resource(loader.getResourceAsStream("scalamode-linker/classpath.txt"))(input =>
      new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).linesIterator.toVector
    )
    assert(names.exists(_.contains("yallmo-linker-worker_3-")))
    assert(names.exists(_.contains("scalajs-linker_3-1.22.0.jar")))
    assert(names.exists(_.contains("scala3-library_3-3.10.1.jar")))
    assert(
      !names.exists(name =>
        List("yallmo-core", "yallmo-server", "yallmo-model", "yallmo-scalacode-api", "-tests").exists(name.contains)
      )
    )
    names.foreach(name =>
      Using.resource(loader.getResourceAsStream(s"scalamode-linker/$name"))(input => assert(input != null))
    )
    run(ZIO.scoped {
      for {
        root <- BundledJsArtifacts.stage()
        failure <- fmgp.yallmo.tool.scalamode.ScalaJsCompiler
          .makeWithToolingResources(
            BundledJsArtifacts.artifactNames.map(root.resolve),
            _ => null
          )
          .either
        _ <- ZIO.attempt(assert(failure.isLeft))
      } yield ()
    })
  }

  test("missing Node disables production pure capability without changing JVM availability") {
    assume(sys.env.get("YALLMO_TEST_EXPECT_NO_NODE").contains("1"), "Run with a PATH containing Scala CLI but no Node")
    run(ZIO.scoped(for {
      manual <- ScalaJsRuntime.provisionBundled
      _ <- ZIO.attempt { assert(!manual.capabilities.jsNodeAvailable); assertEquals(manual.executor, None) }
      jvmAvailable <- ScalaCodeExecutors.make().isAvailable
      _ <- ZIO.attempt(assert(jvmAvailable))
    } yield ()))
  }

  test("missing and partially copied resources close streams and remove staging immediately even in a live scope") {
    for failAt <- List(1, 2) do {
      val before = roots
      val opened = new AtomicInteger()
      val closed = new AtomicInteger()
      val open: String => InputStream = _ => {
        val index = opened.incrementAndGet()
        if index == failAt && failAt == 1 then null
        else
          new InputStream {
            private val data = new ByteArrayInputStream(Array[Byte](1, 2, 3))
            private var copied = false
            override def read(): Int = {
              if index == failAt && copied then throw new IOException("private partial copy failure")
              copied = true
              data.read()
            }
            override def close(): Unit = { closed.incrementAndGet(); data.close() }
          }
      }
      run(ZIO.scoped {
        for {
          result <- BundledJsArtifacts.stage(open).exit
          _ <- ZIO.succeed {
            assert(result.isFailure)
            assertEquals(roots, before)
            assertEquals(closed.get(), if failAt == 1 then 0 else 2)
          }
        } yield ()
      })
    }
  }

  test("staging plus cleanup I/O failure logs both causes, returns sanitized unavailable and lets host work continue") {
    for cleanupFailure <- List[Throwable](
        new IOException("/private/cleanup-failure"),
        new UncheckedIOException(new IOException("/private/walk-failure"))
      )
    do {
      val logs = new CopyOnWriteArrayList[String]()
      val capture = ZLogger.default.map(line => { logs.add(line); () })
      val failedRoot = new AtomicReference[Path]()
      val cleanupCalls = new AtomicInteger()
      val opened = new AtomicInteger()
      val copyFailure = new IOException("/private/original-copy-failure")
      val staging = BundledJsArtifacts.stage(
        openResource = _ => {
          if opened.incrementAndGet() == 2 then throw copyFailure
          new ByteArrayInputStream(Array[Byte](1))
        },
        removeRoot = root =>
          ZIO.succeed { failedRoot.set(root); cleanupCalls.incrementAndGet() } *>
            ZIO.fail(cleanupFailure)
      )
      try {
        run(ZIO.scoped {
          for {
            _ <- (Runtime.removeDefaultLoggers >>> Runtime.addLogger(capture)).build
            hostWork <- Ref.make(0)
            manual <- ScalaJsRuntime.provisionBundled(staging)
            _ <- hostWork.update(_ + 42)
            completed <- hostWork.get
            _ <- ZIO.succeed {
              assertEquals(completed, 42)
              assertEquals(manual.executor, None)
              assert(!manual.capabilities.jsNodeAvailable)
              assertEquals(
                manual.capabilities.reason,
                "JS / Node backend unavailable: check server Node, Scala CLI and reviewed artifacts"
              )
              assert(Files.exists(failedRoot.get()))
              assertEquals(cleanupCalls.get(), 1)
              assert(
                logs.asScala.exists(line => line.contains("level=WARN") && line.contains(cleanupFailure.getMessage))
              )
              assert(logs.asScala.exists(line => line.contains("level=ERROR") && line.contains(copyFailure.getMessage)))
            }
          } yield ()
        })
        assertEquals(cleanupCalls.get(), 1)
      } finally Option(failedRoot.get()).foreach(removeTestRoot)
    }
  }

  test("startup interruption stays interruption even when artifact cleanup fails with I/O") {
    val logs = new CopyOnWriteArrayList[String]()
    val capture = ZLogger.default.map(line => { logs.add(line); () })
    val failedRoot = new AtomicReference[Path]()
    try
      run(ZIO.scoped {
        for {
          _ <- (Runtime.removeDefaultLoggers >>> Runtime.addLogger(capture)).build
          staged <- Promise.make[Nothing, Unit]
          returnedCapability <- Ref.make(false)
          staging = BundledJsArtifacts
            .stage(
              removeRoot =
                root => ZIO.succeed(failedRoot.set(root)) *> ZIO.fail(new IOException("cancel cleanup failure"))
            )
            .tap(_ => staged.succeed(())) *> ZIO.never
          fiber <- ZIO.scoped(ScalaJsRuntime.provisionBundled(staging).tap(_ => returnedCapability.set(true))).fork
          _ <- staged.await
          result <- fiber.interrupt
          returned <- returnedCapability.get
          _ <- ZIO.succeed {
            assert(result.isInterrupted)
            assert(result.causeOption.exists(cause => cause.defects.isEmpty && cause.failures.isEmpty))
            assert(!returned)
            assert(Files.exists(failedRoot.get()))
            assert(logs.asScala.exists(_.contains("cancel cleanup failure")))
            assert(!logs.asScala.exists(_.contains("Bundled manual JS provisioning failed")))
          }
        } yield ()
      })
    finally Option(failedRoot.get()).foreach(removeTestRoot)
  }

  test("unrelated cleanup failures and defects are not converted to optional JS unavailability") {
    val defect = new IllegalStateException("cleanup programming defect")
    for cleanup <- List(
        ZIO.fail(defect),
        ZIO.die(defect),
        ZIO.fail(new IOException("cleanup I/O plus defect")).ensuring(ZIO.die(defect))
      )
    do {
      val failedRoot = new AtomicReference[Path]()
      try {
        val result = run(
          ZIO
            .scoped(
              ScalaJsRuntime.provisionBundled(
                BundledJsArtifacts.stage(
                  openResource = _ => throw new IOException("staging failed"),
                  removeRoot = root => ZIO.succeed(failedRoot.set(root)) *> cleanup
                )
              )
            )
            .exit
        )
        assert(result.causeOption.exists(_.defects.contains(defect)))
      } finally Option(failedRoot.get()).foreach(removeTestRoot)
    }
  }

  test("cancellation during partial staging closes streams and removes the root before the owner scope closes") {
    val before = roots
    val started = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val opened = new AtomicInteger()
    val closed = new AtomicInteger()
    val open: String => InputStream = _ => {
      if opened.incrementAndGet() == 2 then {
        started.countDown()
        if !release.await(10, TimeUnit.SECONDS) then throw new IOException("Test release timed out")
      }
      new ByteArrayInputStream(Array[Byte](1)) {
        override def close(): Unit = { closed.incrementAndGet(); super.close() }
      }
    }
    run(
      ZIO
        .scoped {
          for {
            fiber <- BundledJsArtifacts.stage(open).fork
            _ <- ZIO.attemptBlocking(assert(started.await(10, TimeUnit.SECONDS)))
            _ <- fiber.interruptFork
            _ <- ZIO.succeed(release.countDown())
            result <- fiber.await
            _ <- ZIO.succeed {
              assert(result.isInterrupted)
              assertEquals(roots, before)
              assertEquals(closed.get(), 2)
            }
          } yield ()
        }
        .ensuring(ZIO.succeed(release.countDown()))
    )
  }

  test("missing Scala CLI or Node has a sanitized unavailable capability, never a JVM executor") {
    for (cli, node) <- List(("/private/missing-scala-cli", "node"), ("scala-cli", "/private/missing-node")) do {
      val manual = run(ZIO.scoped {
        BundledJsArtifacts.stage().flatMap { root =>
          ScalaCodeExecutors
            .node(BundledJsArtifacts.artifactNames.map(root.resolve), cli, node)
            .flatMap(ScalaJsRuntime.checked)
        }
      })
      assertEquals(manual.executor, None)
      assert(!manual.capabilities.jsNodeAvailable)
      assert(!manual.capabilities.reason.contains("/private"))
    }
  }

  test("bundled provisioning executes real fresh JS code to 42 without an external staging root") {
    assume(
      sys.env.get("YALLMO_TEST_BUNDLED_JS").contains("1"),
      "Set YALLMO_TEST_BUNDLED_JS=1 with PATH Node/Scala CLI and offline caches"
    )
    run(ZIO.scoped {
      for {
        manual <- ScalaJsRuntime.provisionBundled
        _ <- ZIO.succeed(assert(manual.capabilities.jsNodeAvailable, manual.capabilities.reason))
        result <- manual.executor.get.execute("ZIO.succeed(40 + 2)", 90.seconds, 8192, Seq.empty)
        _ <- ZIO.succeed {
          assertEquals(result.exitCode, 0)
          assert(result.output.contains("42"), result.toString)
        }
      } yield ()
    })
  }
}
