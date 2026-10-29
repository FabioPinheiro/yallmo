package fmgp.yallmo.view.web

import fmgp.yallmo.model.*
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.time.Instant
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.timers.setTimeout
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

final class ScalamodeModelSuite extends FunSuite {
  private val human = AgentId("human")
  private val agent = AgentId("assistant")
  private val info = Initialize.Result(
    Version,
    "test",
    Identity(human, Role.Operator),
    List(AgentsInvoke.method),
    Nil,
    MaxBlobChunkBytes,
    Some(Chunk(agent))
  )

  private final class Harness {
    val requests = mutable.ArrayBuffer.empty[(AgentsInvoke.Params, Promise[Message])]
    var selection: Option[AgentId] = Some(agent)
    val model = new ScalamodeModel(
      params => {
        val promise = Promise[Message]()
        requests += ((params, promise))
        promise.future
      },
      () => selection
    )
    model.configure(info)

    def response(output: String = "12\n"): Message = {
      val input = requests.last._1.input
      val call = input.payload.asInstanceOf[MessagePayload.ToolCall].call
      Message(
        MessageData(
          requests.last._1.agentId,
          Some(input.id),
          Instant.EPOCH,
          Message.ToolOutput(
            FunctionCallOutput(
              call.id,
              call.name,
              Json
                .Obj(
                  "exitCode" -> Json.Num(0),
                  "timedOut" -> Json.Bool(false),
                  "truncated" -> Json.Bool(false),
                  "output" -> Json.Str(output)
                )
                .toJson
            )
          )
        )
      )
    }
  }

  test("Run sends one correlated scalamode call, preserves drafts, and displays the response") {
    val h = new Harness
    h.model.code("List(1, 2, 3).map(_ * 2).sum")
    h.model.run()
    h.model.run()
    assertEquals(h.requests.size, 1)
    val params = h.requests.head._1
    val call = params.input.payload.asInstanceOf[MessagePayload.ToolCall].call
    assertEquals(params.agentId, agent)
    assertEquals(params.input.agentId, human)
    assertEquals(params.input.parentId, None)
    assertEquals(h.model.state.now().tool, ScalamodeTool.Safe)
    assertEquals(call.name, "scalamode")
    assertEquals(call.argumentsJson.fromJson[Json], Right(Json.Obj("code" -> Json.Str("List(1, 2, 3).map(_ * 2).sum"))))
    h.model.code("next draft")
    val response = h.response()
    h.requests.head._2.success(response)
    tick().map { _ =>
      assert(!h.model.state.now().pending)
      assertEquals(h.model.state.now().code, "next draft")
      assertEquals(h.model.state.now().result.map(_.message), Some(response))
    }
  }

  test("each run button submits its own tool key without changing source") {
    val h = new Harness
    val source = "ZIO.succeed(42)"
    h.model.code(source)
    assertEquals(h.requests.size, 0)
    h.model.run(ScalamodeTool.Dynamic)
    assertEquals(h.model.state.now().tool, ScalamodeTool.Dynamic)
    assertEquals(h.model.state.now().code, source)
    val call = h.requests.head._1.input.payload.asInstanceOf[MessagePayload.ToolCall].call
    assertEquals(call.name, "scalaunsafe")
    assertEquals(call.argumentsJson.fromJson[Json], Right(Json.Obj("code" -> Json.Str(source))))
    val response = h.response("42")
    h.requests.head._2.success(response)
    tick().map { _ =>
      assertEquals(h.model.state.now().result.map(_.message), Some(response))
      assert(!h.model.state.now().pending)
      h.model.run(ScalamodeTool.Safe)
      assertEquals(h.model.state.now().tool, ScalamodeTool.Safe)
      assertEquals(h.model.state.now().code, source)
      assertEquals(h.requests.size, 2)
      val safeCall = h.requests.last._1.input.payload.asInstanceOf[MessagePayload.ToolCall].call
      assertEquals(safeCall.name, "scalamode")
      assertEquals(safeCall.argumentsJson, call.argumentsJson)
    }
  }

  test("neither run button can retarget or duplicate a pending request") {
    Future.sequence(ScalamodeTool.values.toList.map { tool =>
      val h = new Harness
      h.model.run(tool)
      ScalamodeTool.values.foreach(h.model.run)
      assertEquals(h.model.state.now().tool, tool)
      assertEquals(h.requests.size, 1)
      assert(h.model.state.now().blockedReason(Some(agent)).exists(_.contains(s"Waiting for ${tool.key}")))
      h.requests.head._2.success(h.response())
      tick().map { _ => assert(!h.model.state.now().pending) }
    })
  }

  test("last submitted tool and source survive reconnect without replay or automatic fallback") {
    val h = new Harness
    h.model.code("ZIO.succeed(42)")
    h.model.run(ScalamodeTool.Dynamic)
    h.model.disable()
    h.model.configure(info)
    h.requests.head._2.success(h.response())
    tick().map { _ =>
      assertEquals(h.model.state.now().tool, ScalamodeTool.Dynamic)
      assertEquals(h.model.state.now().code, "ZIO.succeed(42)")
      assertEquals(h.model.state.now().result, None)
      assertEquals(h.requests.size, 1)
    }
  }

  test("a dynamic call rejects a response naming the restricted tool") {
    val h = new Harness
    h.model.run(ScalamodeTool.Dynamic)
    val response = h.response()
    val output = response.payload.asInstanceOf[MessagePayload.ToolOutput].output
    h.requests.head._2
      .success(Message(response.data.copy(payload = Message.ToolOutput(output.copy(name = "scalamode")))))
    tick().map { _ =>
      assertEquals(h.model.state.now().result, None)
      assert(h.model.state.now().notice.contains("Uncorrelated"))
      assertEquals(h.requests.size, 1)
    }
  }

  test("viewers, missing methods, unknown agents, empty and oversized code never invoke") {
    val h = new Harness
    val variants = List(
      info.copy(identity = Identity(human, Role.Viewer)),
      info.copy(methods = Nil),
      info.copy(configuredAgentIds = Some(Chunk.empty))
    )
    variants.foreach { variant => h.model.configure(variant); h.model.run() }
    h.model.configure(info)
    h.selection = Some(AgentId("not-configured"))
    h.model.run()
    h.selection = Some(agent)
    h.model.code(" ")
    h.model.run()
    h.model.code("x" * 65537)
    h.model.run()
    assertEquals(h.requests.size, 0)
  }

  test("Run reads the shared Chat selection and pending results retain their original recipient") {
    val h = new Harness
    val other = AgentId("other")
    h.model.configure(info.copy(configuredAgentIds = Some(Chunk(agent, other))))
    h.selection = None
    h.model.run()
    assertEquals(h.requests.size, 0)
    assert(h.model.state.now().notice.contains("toolbar"))
    h.selection = Some(agent)
    h.model.run()
    val firstResponse = h.response()
    // A conversation switch may change shared selection; it must not retarget an admitted request.
    h.selection = Some(other)
    h.model.run()
    assertEquals(h.requests.size, 1)
    h.requests.head._2.success(firstResponse)
    tick().flatMap { _ =>
      assertEquals(h.model.state.now().result.map(_.message.agentId), Some(agent))
      h.model.run()
      assertEquals(h.requests.last._1.agentId, other)
      h.requests.last._2.success(h.response())
      tick().map { _ =>
        assertEquals(h.model.state.now().result.map(_.message.agentId), Some(other))
      }
    }
  }

  test("disconnect invalidates late results, preserves the draft, and never replays on reconnect") {
    val h = new Harness
    h.model.run()
    h.model.disable()
    assert(h.model.state.now().notice.contains("unknown"))
    h.model.configure(info)
    h.requests.head._2.success(h.response())
    tick().map { _ =>
      assertEquals(h.requests.size, 1)
      assertEquals(h.model.state.now().result, None)
      assert(!h.model.state.now().pending)
      assert(h.model.state.now().code.contains("tool.api"))
    }
  }

  test("incorrect parent, agent, call ID, tool name or payload cannot be displayed as a result") {
    val changes: List[Message => Message] = List(
      message => Message(message.data.copy(parentId = None)),
      message => Message(message.data.copy(agentId = AgentId("wrong"))),
      message =>
        Message(
          message.data
            .copy(payload = Message.ToolOutput(FunctionCallOutput(FunctionCallId.unsafe("wrong"), "scalamode", "12")))
        ),
      message =>
        Message(
          message.data.copy(payload =
            Message.ToolOutput(
              FunctionCallOutput(message.payload.asInstanceOf[MessagePayload.ToolOutput].output.callId, "wrong", "12")
            )
          )
        ),
      message => Message(message.data.copy(payload = Message.Assistant("not a tool result")))
    )
    Future.sequence(changes.map { change =>
      val h = new Harness
      h.model.run()
      h.requests.head._2.success(change(h.response()))
      tick().map { _ =>
        assertEquals(h.model.state.now().result, None)
        assert(h.model.state.now().notice.contains("Uncorrelated"))
      }
    })
  }

  test("failed calls retain source, expose recovery guidance and require a new manual Run") {
    val h = new Harness
    h.model.run()
    h.requests.head._2.failure(new RuntimeException("Connection lost"))
    tick().map { _ =>
      assert(!h.model.state.now().pending)
      assert(h.model.state.now().notice.contains("Connection lost"))
      assertEquals(h.requests.size, 1)
      h.model.run()
      assertEquals(h.requests.size, 2)
      assertNotEquals(h.requests.head._1.input.payload, h.requests.last._1.input.payload)
    }
  }

  test("result fields preserve decoded output and whitespace without interpreting markup") {
    val h = new Harness
    h.model.run()
    val output = "<script>untrusted</script>\r\n"
    val result = ScalamodeResult(h.response(output))
    assertEquals(result.fields.flatMap(_.blocks.find(_.name == "output")).map(_.text), Some(output))
  }

  private def tick(): Future[Unit] = {
    val result = Promise[Unit]()
    val _ = setTimeout(20)(result.success(()))
    result.future
  }
}
