package fmgp.yallmo.tool

import fmgp.yallmo.orchestration.ExternalToolCall
import munit.FunSuite
import zio.*

import java.nio.file.Path

final class BashToolSuite extends FunSuite {
  private val tool = new BashTool(workspace = Path.of("").toAbsolutePath.normalize)

  test("bash tool returns the command exit code and combined output") {
    val result = run(
      tool
        .execute(ExternalToolCall("bash", """{"command":"printf hello; printf error >&2"}"""))
        .provide(BashExecutor.live)
    )
    val output = ujson.read(result.text)

    assertEquals(output("exitCode").num.toInt, 0)
    assertEquals(output("timedOut").bool, false)
    assertEquals(output("output").str, "helloerror")
    assertEquals(output("truncated").bool, false)
  }

  test("bash tool rejects malformed arguments") {
    val result = run(tool.execute(ExternalToolCall("bash", "{}")).either.provide(BashExecutor.live))

    assert(result.isLeft)
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }
}
