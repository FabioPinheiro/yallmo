package fmgp.yallmo.scalacode

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
