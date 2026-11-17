package fmgp.yallmo.tool.scalamode

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

final class ScalaToolRuntimeSuite extends FunSuite {
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def withLog(test: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("yallmo-json-logger-")
    val path = directory.resolve("runtime.jsonl")
    try test(path)
    finally {
      Files.deleteIfExists(path)
      Files.deleteIfExists(directory)
    }
  }

  test("evaluation returns plain values and executes returned effects lazily") {
    var evaluated = 0
    val plain = ScalaToolRuntime.evaluate { evaluated += 1; 42 }
    assertEquals(evaluated, 0)
    assertEquals(run(plain), 42)
    val effect = ScalaToolRuntime.evaluate(ZIO.succeed { evaluated += 1; 2 })
    assertEquals(evaluated, 1)
    assertEquals(run(effect), 2)
    assertEquals(evaluated, 2)
    assertEquals(run(ScalaToolRuntime.evaluate(ZIO.fail("expected")).either), Left("expected"))
  }

  test("the optional setup marker precedes plain and effect evaluation without changing failures") {
    val directory = Files.createTempDirectory("scalamode-setup-marker-")
    val marker = directory.resolve(ScalaToolRuntime.ExecutionStartedFile)
    val property = ScalaToolRuntime.ExecutionMarkerProperty
    val previous = Option(java.lang.System.getProperty(property))
    try {
      java.lang.System.setProperty(property, marker.toString)
      assertEquals(run(ScalaToolRuntime.evaluate { assert(Files.exists(marker)); 42 }), 42)
      Files.delete(marker)
      val failure = ScalaToolRuntime.evaluate {
        assert(Files.exists(marker))
        ZIO.fail("expected")
      }
      assertEquals(run(failure.either), Left("expected"))
      // Repeated evaluations and failed marker writes must not fail the program.
      assertEquals(run(ScalaToolRuntime.evaluate(43)), 43)
      java.lang.System.setProperty(property, directory.resolve("missing/marker").toString)
      assertEquals(run(ScalaToolRuntime.evaluate(44)), 44)
    } finally {
      previous match {
        case Some(value) => java.lang.System.setProperty(property, value)
        case None        => java.lang.System.clearProperty(property)
      }
      Files.deleteIfExists(marker)
      Files.delete(directory)
    }
  }

  test("evaluation suspends effect construction and preserves finalizers") {
    var finalized = false
    val effect = ScalaToolRuntime.evaluate(ZIO.succeed(2).ensuring(ZIO.succeed { finalized = true }))
    assert(!finalized)
    assertEquals(run(effect), 2)
    assert(finalized)
    assert(run(ScalaToolRuntime.evaluate { throw new IllegalStateException("construction") }.exit).isFailure)
  }

  test("compiled pure runner preserves typed failures, lazy evaluation and finalizers") {
    var finalized = false
    var evaluated = false
    val effect: ZIO[Any, String | Throwable, Unit] = ScalaToolRuntime.runPure { _ =>
      evaluated = true
      ScalaToolRuntime.evaluate(ZIO.fail("expected").ensuring(ZIO.succeed { finalized = true }))
    }
    assert(!evaluated)
    assertEquals(run(effect.either), Left("expected"))
    assert(evaluated)
    assert(finalized)
  }

  test("compiled native read contract maps every infrastructure error without transport details") {
    import fmgp.rpc.*
    for code <- RpcError.Code.values do {
      val client = NativeReadToolsRuntime.contract.client(new RpcConnection {
        def call(serviceId: String, version: Int, method: String, arguments: Chunk[Json]) = ZIO.fail(RpcError(code))
        def close = ZIO.unit
      })
      assertEquals(run(client.readFile("a").either), Left(ReadToolError("RpcError", code.toString)))
      assertEquals(run(client.listFiles("a").either), Left(ReadToolError("RpcError", code.toString)))
    }
  }

  test("runtime logger writes structured JSON lines with escaping, annotations and failure causes") {
    withLog { path =>
      val message = "quoted \"value\", slash \\, tab\tand newline\nUnicode: λ"
      run((for {
        _ <- ZIO.logDebug("hidden debug record")
        _ <- ZIO.logAnnotate("request", "test\"request")(ZIO.logInfo(message))
        _ <- ZIO.logErrorCause("failure", Cause.fail("expected failure"))
      } yield ()).provideLayer(Runtime.removeDefaultLoggers >>> ScalaToolRuntime.fileLogger(path, 65536)))
      val lines = Files.readString(path, UTF_8).linesIterator.toList
      assertEquals(lines.length, 2)
      val records = lines.map(line => line.fromJson[Json].fold(error => fail(error), identity))
      assert(records.forall(_.isInstanceOf[Json.Obj]))
      assert(lines.head.contains("\"level\":\"INFO\""), lines.head)
      assert(lines.head.contains(message.toJson), lines.head)
      assert(lines.head.contains("test\\\"request"), lines.head)
      assert(lines.last.contains("expected failure"), lines.last)
      assert(!lines.exists(_.contains("hidden debug record")))
    }
  }

  test("runtime log file is bounded to the configured byte prefix plus one overflow byte") {
    withLog { path =>
      run(
        ZIO
          .foreachParDiscard(1 to 50)(_ => ZIO.logInfo("x" * 1000))
          .provideLayer(Runtime.removeDefaultLoggers >>> ScalaToolRuntime.fileLogger(path, 64))
      )
      assertEquals(Files.size(path), 65L)
    }
  }

  test("concurrent logger records remain separate valid JSON lines") {
    withLog { path =>
      run(
        ZIO
          .foreachParDiscard(1 to 30)(index => ZIO.logInfo(s"event $index"))
          .provideLayer(Runtime.removeDefaultLoggers >>> ScalaToolRuntime.fileLogger(path, 65536))
      )
      val lines = Files.readString(path, UTF_8).linesIterator.toList
      assertEquals(lines.size, 30)
      assert(lines.forall(_.fromJson[Json].isRight))
    }
  }

  test("file logger rejects nonpositive byte limits") {
    withLog { path =>
      val result = run(ZIO.unit.provideLayer(ScalaToolRuntime.fileLogger(path, 0)).either)
      assert(result.isLeft)
      assert(!Files.exists(path))
    }
  }
}
