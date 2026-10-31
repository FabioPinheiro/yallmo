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

    def response(output: String = "12\n", logs: Seq[String] = Seq.empty): Message = {
      val input = requests.last._1.input
      val call = input.payload.asInstanceOf[MessagePayload.ToolCall].call
      Message(
        MessageData(
          requests.last._1.agentId,
          Some(input.id),
          Instant.EPOCH,
          Message.ToolOutput(
            ScalaModeOutput(call.id, ScalaModeResult.Executed(output), logs)
          )
        )
      )
    }
  }

  test("the default example lists the selected root using filesystem mode") {
    val h = new Harness
    val code = h.model.state.now().code
    assert(code.contains("""fs.list(".")"""))
    h.model.run()
    val call = h.requests.head._1.input.payload.asInstanceOf[MessagePayload.ToolCall].call
    assertEquals(
      call.argumentsJson.fromJson[Json],
      Right(Json.Obj("code" -> Json.Str(code), "mode" -> Json.Str("filesystem")))
    )
  }

  test("Run sends one correlated scalamode call, preserves drafts, and displays the response") {
    val h = new Harness
    h.model.code("ZIO.succeed(List(1, 2, 3).map(_ * 2).sum)")
    h.model.run()
    h.model.run()
    assertEquals(h.requests.size, 1)
    val params = h.requests.head._1
    val call = params.input.payload.asInstanceOf[MessagePayload.ToolCall].call
    assertEquals(params.agentId, agent)
    assertEquals(params.input.agentId, human)
    assertEquals(params.input.parentId, None)
    assertEquals(call.name, ToolKey.Scalamode)
    assertEquals(
      call.argumentsJson.fromJson[Json],
      Right(Json.Obj("code" -> Json.Str("ZIO.succeed(List(1, 2, 3).map(_ * 2).sum)"), "mode" -> Json.Str("filesystem")))
    )
    h.model.code("next draft")
    val response = h.response()
    h.requests.head._2.success(response)
    tick().map { _ =>
      assert(!h.model.state.now().pending)
      assertEquals(h.model.state.now().code, "next draft")
      assertEquals(h.model.state.now().result.map(_.message), Some(response))
    }
  }

  test("diagnostics stay separate from the displayed result") {
    val h = new Harness
    h.model.run()
    val result = ScalamodeResult(h.response("42\n", Seq("connected", "disconnected")))
    assertEquals(result.logs, Seq("connected", "disconnected"))
    assertEquals(result.fields.get.blocks.find(_.name == "output").map(_.text), Some("42\n"))
    assert(!result.output.contains("connected"))
  }

  test("phase timings are displayed with millisecond units for every result type") {
    val h = new Harness
    h.model.run()
    val response = h.response()
    val callId = response.payload.asInstanceOf[MessagePayload.ToolOutput].output.callId
    val results = List(
      ScalaModeResult.Executed("42"),
      ScalaModeResult.CompilationError("compiler error"),
      ScalaModeResult.TimedOut("deadline exceeded")
    )
    results.foreach { outcome =>
      val output = ScalaModeOutput(
        callId,
        outcome,
        compilationTime = 1200L,
        executionTime = 250L,
        totalTime = 1500L
      )
      val result = ScalamodeResult(Message(response.data.copy(payload = Message.ToolOutput(output))))
      assertEquals(
        result.fields.get.metadata.take(3),
        List(
          ToolResultContent.Field("compilationTime", "1200 ms"),
          ToolResultContent.Field("executionTime", "250 ms"),
          ToolResultContent.Field("totalTime", "1500 ms")
        )
      )
      assertEquals(result.output, outcome.output)
    }
  }

  test("unmeasured or skipped phase timings display zero milliseconds") {
    val h = new Harness
    h.model.run()
    val result = ScalamodeResult(h.response())
    assertEquals(
      result.fields.get.metadata.take(3),
      List(
        ToolResultContent.Field("compilationTime", "0 ms"),
        ToolResultContent.Field("executionTime", "0 ms"),
        ToolResultContent.Field("totalTime", "0 ms")
      )
    )
  }

  test("typed compilation diagnostics and metadata are displayed without parsing returned JSON") {
    val h = new Harness
    h.model.run()
    val response = h.response()
    val callId = response.payload.asInstanceOf[MessagePayload.ToolOutput].output.callId
    val diagnostic = "[E006] Not Found Error\nNot found: missing"
    val output =
      ScalaModeOutput(callId, ScalaModeResult.CompilationError(diagnostic), Seq("compiler"), 1, truncated = true)
    val result = ScalamodeResult(Message(response.data.copy(payload = Message.ToolOutput(output))))
    assertEquals(result.output, diagnostic)
    assertEquals(result.logs, Seq("compiler"))
    assert(result.title.contains("diagnostics"))
    assert(result.fields.get.metadata.contains(ToolResultContent.Field("exitCode", "1")))
    assert(result.fields.get.metadata.contains(ToolResultContent.Field("truncated", "true")))
    val returnedJson = ScalaModeOutput(callId, ScalaModeResult.Executed("{\"logs\":[\"value-not-logs\"]}"))
    val success = ScalamodeResult(Message(response.data.copy(payload = Message.ToolOutput(returnedJson))))
    assertEquals(success.logs, Seq.empty)
    assertEquals(success.fields.get.blocks, List(ToolResultContent.Field("output", returnedJson.output)))
  }

  test("typed timeout diagnostics are displayed without a timeout flag") {
    val h = new Harness
    h.model.run()
    val response = h.response()
    val callId = response.payload.asInstanceOf[MessagePayload.ToolOutput].output.callId
    val output = ScalaModeOutput(callId, ScalaModeResult.TimedOut("deadline exceeded"), exitCode = 137)
    val result = ScalamodeResult(Message(response.data.copy(payload = Message.ToolOutput(output))))
    assertEquals(result.title, "Compilation / execution timed out")
    assertEquals(result.output, "deadline exceeded")
    assert(!result.fields.get.metadata.exists(_.name == "timedOut"))
    assert(result.fields.get.metadata.contains(ToolResultContent.Field("exitCode", "137")))
  }

  test("Run cannot duplicate a pending request") {
    val h = new Harness
    h.model.run()
    h.model.run()
    assertEquals(h.requests.size, 1)
    assert(h.model.state.now().blockedReason(Some(agent)).exists(_.contains("Waiting for scalamode")))
    h.requests.head._2.success(h.response())
    tick().map { _ => assert(!h.model.state.now().pending) }
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
    val draft = h.model.state.now().code
    h.model.run()
    h.model.disable()
    assert(h.model.state.now().notice.contains("unknown"))
    h.model.configure(info)
    h.requests.head._2.success(h.response())
    tick().map { _ =>
      assertEquals(h.requests.size, 1)
      assertEquals(h.model.state.now().result, None)
      assert(!h.model.state.now().pending)
      assertEquals(h.model.state.now().code, draft)
    }
  }

  test("incorrect parent, agent, call ID, tool name or payload cannot be displayed as a result") {
    val changes: List[Message => Message] = List(
      message => Message(message.data.copy(parentId = None)),
      message => Message(message.data.copy(agentId = AgentId("wrong"))),
      message =>
        Message(
          message.data
            .copy(payload =
              Message.ToolOutput(ScalaModeOutput(FunctionCallId.unsafe("wrong"), ScalaModeResult.Executed("12")))
            )
        ),
      message =>
        Message(
          message.data.copy(payload =
            Message.ToolOutput(
              FunctionGenericOutput(
                message.payload.asInstanceOf[MessagePayload.ToolOutput].output.callId,
                ToolKey.unsafe("wrong"),
                "12"
              )
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
