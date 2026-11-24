package fmgp.yallmo.webharness

import fmgp.yallmo.server.protocol.YallmoProtocol.{AgentsSkills, SkillSummary, ToolsList}
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

  test("inspection is lazy, double clicks send once and collapse discards schemas before the next inspection") {
    val h = new Harness
    assert(h.requests.isEmpty)
    assertEquals(h.model.state.now().tools, None)
    assert(!h.model.expanded.now())
    h.model.toggle()
    h.model.toggle()
    assertEquals(h.requests.size, 1)
    assert(h.model.state.now().loading)
    assert(h.model.expanded.now())
    h.requests.head.success(tools)
    tick().flatMap { _ =>
      assertEquals(h.model.state.now().tools, Some(tools))
      assert(!h.model.state.now().loading)
      h.model.toggle()
      assert(!h.model.expanded.now())
      assertEquals(h.model.state.now().tools, None)
      assertEquals(h.requests.size, 1)
      h.model.toggle()
      assert(h.model.expanded.now())
      assert(h.model.state.now().loading)
      assertEquals(h.requests.size, 2)
      h.requests.last.success(Chunk.empty)
      tick().map { _ => assertEquals(h.model.state.now().tools, Some(Chunk.empty)) }
    }
  }

  test("collapsing discards empty tool results too, and reset invalidates the next inspection") {
    val h = new Harness
    h.model.toggle()
    h.requests.head.success(Chunk.empty)
    tick().map { _ =>
      h.model.toggle()
      assert(!h.model.expanded.now())
      assertEquals(h.model.state.now().tools, None)
      assertEquals(h.requests.size, 1)
      h.model.toggle()
      assert(h.model.expanded.now())
      assert(h.model.state.now().loading)
      assertEquals(h.requests.size, 2)
      h.model.reset()
      assert(!h.model.expanded.now())
      assertEquals(h.model.state.now().tools, None)
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
    h.model.toggle()
    h.requests.head.success(Chunk(ToolsList.Tool(ToolKey.Scalamode, Chunk(definition))))
    tick().map { _ =>
      assertEquals(h.model.state.now().tools.get.head.functions.head.scalamode, Some(api))
      assertEquals(h.model.state.now().tools.get.head.functions.head.scalamode.get.services.size, 2)
    }
  }

  test("failures are visible, never implicitly retried and explicit retry can recover") {
    val h = new Harness
    h.model.toggle()
    h.requests.head.failure(new RuntimeException("Unavailable"))
    tick().flatMap { _ =>
      assertEquals(h.model.state.now().error, Some("Unavailable"))
      assert(!h.model.state.now().loading)
      assertEquals(h.requests.size, 1)
      h.model.toggle()
      assertEquals(h.model.state.now().error, None)
      h.requests.last.success(tools)
      tick().map { _ => assertEquals(h.model.state.now().tools, Some(tools)) }
    }
  }

  test("reset invalidates unmounted or reconnected reads without overwriting a newer request") {
    val h = new Harness
    h.model.toggle()
    h.model.reset()
    h.model.toggle()
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
    model.toggle()
    tick().map { _ =>
      assertEquals(model.state.now().error, Some("Disconnected"))
      assert(!model.state.now().loading)
    }
  }

  private final class SkillsHarness {
    val requests = mutable.ArrayBuffer.empty[Promise[AgentsSkills.Result]]
    val model = new AgentSkillsModel(() => {
      val request = Promise[AgentsSkills.Result]()
      requests += request
      request.future
    })
  }

  test("skills remain uninspected until requested, and inspection is independent of tools") {
    val h = new SkillsHarness
    assert(h.requests.isEmpty)
    assertEquals(h.model.state.now().value, None)
    assert(!h.model.state.now().loading)
    h.model.toggle()
    h.model.toggle()
    assertEquals(h.requests.size, 1)
    assert(h.model.state.now().loading)
    val result = AgentsSkills.Result(Chunk(SkillSummary("scala-developer", "Scala work", false, 1, None, None)), true)
    h.requests.head.success(result)
    tick().flatMap { _ =>
      assertEquals(h.model.state.now().value, Some(result))
      assert(!h.model.state.now().loading)
      assert(h.model.expanded.now())
      h.model.toggle()
      assert(!h.model.expanded.now())
      assertEquals(h.model.state.now().value, None)
      assertEquals(h.requests.size, 1)
      h.model.toggle()
      assert(h.model.expanded.now())
      assert(h.model.state.now().loading)
      assertEquals(h.requests.size, 2)
      val updated = AgentsSkills.Result(Chunk.empty, false)
      h.requests.last.success(updated)
      tick().map { _ => assertEquals(h.model.state.now().value, Some(updated)) }
    }
  }

  test("collapsing discards empty skill results and the next inspection fetches again") {
    val h = new SkillsHarness
    h.model.toggle()
    val result = AgentsSkills.Result(Chunk.empty, false)
    h.requests.head.success(result)
    tick().map { _ =>
      h.model.toggle()
      assert(!h.model.expanded.now())
      assertEquals(h.model.state.now().value, None)
      assertEquals(h.requests.size, 1)
      h.model.toggle()
      assert(h.model.expanded.now())
      assert(h.model.state.now().loading)
      assertEquals(h.requests.size, 2)
      assertEquals(h.model.state.now().value, None)
    }
  }

  test("skill inspection failures require explicit retry and reset invalidates late results") {
    val h = new SkillsHarness
    h.model.toggle()
    h.requests.head.failure(new RuntimeException("Agent skill context unavailable"))
    tick().flatMap { _ =>
      assertEquals(h.model.state.now().error, Some("Agent skill context unavailable"))
      assertEquals(h.requests.size, 1)
      h.model.toggle()
      h.model.reset()
      h.requests.last.success(AgentsSkills.Result(Chunk.empty, false))
      tick().map { _ =>
        assertEquals(h.model.state.now().value, None)
        assertEquals(h.model.state.now().error, None)
        assert(!h.model.state.now().loading)
        assert(!h.model.expanded.now())
        assertEquals(h.requests.size, 2)
      }
    }
  }

  private def tick(): Future[Unit] = {
    val done = Promise[Unit]()
    setTimeout(0)(done.success(()))
    done.future
  }
}
