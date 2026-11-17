package fmgp.yallmo.webharness

import fmgp.yallmo.server.protocol.YallmoProtocol.ToolsList
import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition, ScalamodeApi, ScalamodeServiceApi, ToolKey}
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.timers.setTimeout
import zio.Chunk

final class AgentToolsModelSuite extends FunSuite {
  private val tools = Chunk(
    ToolsList.Tool(
      ToolKey.unsafe("inspection"),
      Chunk(FunctionToolDefinition(FunctionKey.unsafe("inspect"), "Read-only inspection"))
    )
  )

  private final class Harness {
    val requests = mutable.ArrayBuffer.empty[Promise[ToolsList.Result]]
    val model = new AgentToolsModel(() => {
      val request = Promise[ToolsList.Result]()
      requests += request
      request.future
    })
  }

  test("inspection is lazy, double clicks send once and successful schemas can be explicitly reloaded") {
    val h = new Harness
    assert(h.requests.isEmpty)
    assertEquals(h.model.state.now().tools, None)
    h.model.reload()
    h.model.reload()
    assertEquals(h.requests.size, 1)
    assert(h.model.state.now().loading)
    h.requests.head.success(tools)
    tick().flatMap { _ =>
      assertEquals(h.model.state.now().tools, Some(tools))
      assert(!h.model.state.now().loading)
      h.model.reload()
      assertEquals(h.requests.size, 2)
      assertEquals(h.model.state.now().tools, None)
      h.requests.last.success(Chunk.empty)
      tick().map { _ => assertEquals(h.model.state.now().tools, Some(Chunk.empty)) }
    }
  }

  test("inspection retains every service, API view and Scala declaration from the selected binding") {
    val h = new Harness
    val api = ScalamodeApi(
      "def api: Unit",
      List(
        ScalamodeServiceApi(
          "reads",
          List("example.Reads"),
          "Read-only",
          Some("trait Reads { def readFile(path: String): String }")
        ),
        ScalamodeServiceApi("helpers", List("example.Math", "example.Text"), "Helpers")
      )
    )
    val definition = FunctionToolDefinition(FunctionKey.scalamode, "Evaluate Scala", scalamode = Some(api))
    h.model.reload()
    h.requests.head.success(Chunk(ToolsList.Tool(ToolKey.Scalamode, Chunk(definition))))
    tick().map { _ =>
      assertEquals(h.model.state.now().tools.get.head.functions.head.scalamode, Some(api))
      assertEquals(h.model.state.now().tools.get.head.functions.head.scalamode.get.services.size, 2)
    }
  }

  test("failures are visible, never implicitly retried and explicit retry can recover") {
    val h = new Harness
    h.model.reload()
    h.requests.head.failure(new RuntimeException("Unavailable"))
    tick().flatMap { _ =>
      assertEquals(h.model.state.now().error, Some("Unavailable"))
      assert(!h.model.state.now().loading)
      assertEquals(h.requests.size, 1)
      h.model.reload()
      assertEquals(h.model.state.now().error, None)
      h.requests.last.success(tools)
      tick().map { _ => assertEquals(h.model.state.now().tools, Some(tools)) }
    }
  }

  test("reset invalidates unmounted or reconnected reads without overwriting a newer request") {
    val h = new Harness
    h.model.reload()
    h.model.reset()
    h.model.reload()
    h.requests.head.success(tools)
    tick().flatMap { _ =>
      assert(h.model.state.now().loading)
      assertEquals(h.model.state.now().tools, None)
      h.requests.last.success(Chunk.empty)
      tick().map { _ => assertEquals(h.model.state.now().tools, Some(Chunk.empty)) }
    }
  }

  test("synchronous request failures follow the same visible error path") {
    val model = new AgentToolsModel(() => throw new RuntimeException("Disconnected"))
    model.reload()
    tick().map { _ =>
      assertEquals(model.state.now().error, Some("Disconnected"))
      assert(!model.state.now().loading)
    }
  }

  private def tick(): Future[Unit] = {
    val done = Promise[Unit]()
    setTimeout(0)(done.success(()))
    done.future
  }
}
