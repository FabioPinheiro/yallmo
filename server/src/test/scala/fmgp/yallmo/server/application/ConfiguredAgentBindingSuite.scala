package fmgp.yallmo.server.application

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, LlmStopReason}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{AgentCapabilities, HasAgentCapabilities, ProviderId}
import fmgp.rpc.RpcWebSocket
import fmgp.yallmo.fs.{FsRead, FsError}
import fmgp.yallmo.tool.{ReadFileTool, ScalaCodeExecutor, ScalaTool}
import munit.FunSuite
import zio.*

import scala.concurrent.Future

final class ConfiguredAgentBindingSuite extends FunSuite {
  private val agentId = AgentId("configured")
  private val blob = Blob(BlobId.fresh(), Chunk[Byte](1, 2, 3))
  private val input = Chunk[MessagePayload](
    MessagePayload.User("Review this"),
    MessagePayload.AudioAttachment(blob.id, AudioFormat.Wav)
  )
  private val context = StaticContext[Any](
    "System",
    tools = Chunk("shared", "program-only", "adapter-only").map(key => AgentToolDefinition(ToolKey.unsafe(key), key))
  )
  private val readBlob: BlobId => IO[YallmoError, Blob] = id =>
    if id == blob.id then ZIO.succeed(blob) else ZIO.fail(YallmoError.BlobMissing(id))

  test("native binding retains configured capabilities and supplies the storage blob reader") {
    runZIO {
      for {
        requests <- Ref.make(Chunk.empty[AgentTurnRequest])
        adapter = new Agent[Any] with HasAgentCapabilities {
          override val id: AgentId = agentId
          override val capabilities: AgentCapabilities =
            AgentCapabilities(tools = Some(Set(ToolKey.unsafe("shared"), ToolKey.unsafe("adapter-only"))))
          override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            requests
              .update(_ :+ request)
              .as(
                AgentTurnResponse(Chunk(MessagePayload.Assistant("Native answer")), LlmStopReason.Stop)
              )
        }
        program = NativeFunctionAgent[Agent[Any]](
          agentId,
          "native-model",
          capabilities = AgentCapabilities(tools = Some(Set(ToolKey.unsafe("shared"), ToolKey.unsafe("program-only"))))
        )
        binding = ConfiguredAgentBinding.Native(adapter, ProviderId.unsafe("local"), program, "System")
        bound = binding.bind[Any](readBlob)
        configured <- bound.configuredContext(context)
        turn <- bound.next(AgentTask("Review this"), context, RunContext(input))
        request <- requests.get.map(_.head)
        stored <- request.readBlob(blob.id)
      } yield {
        assertEquals(binding.adapter, adapter)
        assertEquals(binding.systemPrompt, "System")
        assertEquals(bound.id, agentId)
        assertEquals(bound.capabilities.tools, Some(Set(ToolKey.unsafe("shared"))))
        assertEquals(configured.tools.map(_.key), Chunk(ToolKey.unsafe("shared")))
        assertEquals(request.tools.map(_.name), Chunk(ToolKey.unsafe("shared")))
        assertEquals(request.model, "native-model")
        assertEquals(request.input, input :+ MessagePayload.Instructions("System"))
        assertEquals(stored, blob)
        assertEquals(turn.decision, AgentDecision.FinalAnswer("Native answer"))
      }
    }
  }

  test("folder binding disables inherited tools and passes input and blob reader without native generation settings") {
    runZIO {
      for {
        requests <- Ref.make(Chunk.empty[AgentTurnRequest])
        adapter = new Agent[Any] {
          override val id: AgentId = agentId
          override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            requests
              .update(_ :+ request)
              .as(
                AgentTurnResponse(
                  Chunk(MessagePayload.Assistant(" Folder"), MessagePayload.Assistant(" answer ")),
                  LlmStopReason.Stop
                )
              )
        }
        policy = AgentCapabilities()
        binding = ConfiguredAgentBinding.Folder(adapter, policy)
        bound = binding.bind[Any](readBlob)
        configured <- bound.configuredContext(context)
        turn <- bound.next(AgentTask("Review this"), context, RunContext(input))
        request <- requests.get.map(_.head)
        stored <- request.readBlob(blob.id)
      } yield {
        assertEquals(binding.systemPrompt, "")
        assertEquals(bound.id, agentId)
        assertEquals(bound.capabilities, policy.copy(tools = Some(Set.empty)))
        assertEquals(configured.tools, Chunk.empty)
        assertEquals(request.model, "")
        assertEquals(request.toolChoice, ToolChoice.None)
        assertEquals(request.tools, Chunk.empty)
        assertEquals(request.input, input)
        assertEquals(stored, blob)
        assertEquals(turn.assistant, Some(MessagePayload.Assistant(" Folder answer ")))
        assertEquals(turn.decision, AgentDecision.FinalAnswer(" Folder answer "))
      }
    }
  }

  test("folder direct scalamode and readFile invocations deny inherited and explicit tools before capability use") {
    runZIO(ZIO.scoped {
      for {
        used <- Ref.make(false)
        fs = new FsRead {
          def readBytes(path: String): zio.stream.ZStream[Any, FsError, Byte] =
            zio.stream.ZStream.fromZIO(used.set(true).as(1.toByte))
          def list(path: String): IO[FsError, Chunk[String]] = used.set(true).as(Chunk.empty)
        }
        executor = new ScalaCodeExecutor {
          def isAvailable = ZIO.succeed(true)
          def execute(
              code: String,
              timeout: Duration,
              maxOutputBytes: Int,
              dependencies: Seq[String]
          ): Task[ExternalToolResult.ScalaMode] =
            used.set(true).as(ExternalToolResult.ScalaMode(ScalaModeResult.Executed("unexpected")))
          override def executeFilesystem(
              code: String,
              endpoint: String,
              timeout: Duration,
              maxOutputBytes: Int
          ): ZIO[FsRead & RpcWebSocket.RpcRoute, Throwable, ExternalToolResult.ScalaMode] =
            used.set(true).as(ExternalToolResult.ScalaMode(ScalaModeResult.Executed("unexpected")))
        }
        route <- RpcWebSocket.route()
        scalaTool = ScalaTool.filesystem("ws://127.0.0.1:1/rpc")
        tools = Chunk[AgentTool[Any]](
          ApplicationTools.bindFilesystem(new ReadFileTool, fs),
          new AgentTool[Any] {
            val definition = scalaTool.definition
            def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
              scalaTool
                .execute(call)
                .provideEnvironment(ZEnvironment[ScalaCodeExecutor, FsRead, RpcWebSocket.RpcRoute](executor, fs, route))
          }
        )
        adapter = new Agent[Any] {
          val id = agentId
          def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            ZIO.succeed(AgentTurnResponse(Chunk(MessagePayload.Assistant("folder answer")), LlmStopReason.Stop))
        }
        _ <- ZIO.foreachDiscard(
          List(AgentCapabilities(), AgentCapabilities(tools = Some(Set(ToolKey.Scalamode, ToolKey.ReadFile))))
        ) { policy =>
          val binding = ConfiguredAgentBinding.Folder(adapter, policy, Some(fs)).bind[Any](readBlob)
          val execution = new AgentRunner[Any](tools.map(tool => tool.definition.key -> tool).toMap, Map.empty)
            .execution(binding, context.copy(tools = tools.map(_.definition)))
          for {
            _ <- ZIO.foreachDiscard(
              List(
                ToolKey.Scalamode -> """{"code":"fs.readUtf8(\"notes.txt\")","mode":"filesystem"}""",
                ToolKey.ReadFile -> """{"path":"notes.txt"}"""
              )
            ) { (key, arguments) =>
              execution
                .invoke(MessagePayload.ToolCall(FunctionCall(FunctionCallId.unsafe("denied"), key, arguments)))
                .map {
                  case MessagePayload.ToolOutput(output) =>
                    assertEquals(output.name, key)
                    assertEquals(ujson.read(output.output)("error")("kind").str, "ToolUnavailableToAgent")
                  case other => fail(s"Expected denied tool output, got $other")
                }
            }
            answer <- execution.invoke(MessagePayload.User("show folder"))
            _ <- ZIO.attempt(assertEquals(answer, MessagePayload.Assistant("folder answer")))
          } yield ()
        }
        accessed <- used.get
      } yield assert(!accessed, "Folder direct invocation must not execute Scala or access its private FsRead")
    })
  }

  test("folder binding rejects empty, blank, non-text and mixed output") {
    val invalid = Chunk(
      Chunk.empty[MessagePayload],
      Chunk[MessagePayload](MessagePayload.Assistant(" \n\t")),
      Chunk[MessagePayload](MessagePayload.User("Not an assistant answer")),
      Chunk[MessagePayload](MessagePayload.Assistant("Answer"), MessagePayload.Context("extra", "Not text output"))
    )
    runZIO {
      ZIO.foreachDiscard(invalid) { output =>
        val adapter = new Agent[Any] {
          override val id: AgentId = agentId
          override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            ZIO.succeed(AgentTurnResponse(output, LlmStopReason.Stop))
        }
        ConfiguredAgentBinding
          .Folder(adapter, AgentCapabilities())
          .bind[Any](readBlob)
          .next(AgentTask("Review this"), context, RunContext(input))
          .either
          .map(result =>
            assertEquals(
              result,
              Left(YallmoError.InvalidAgentDecision("FolderStructureAgent must return a non-blank text answer"))
            )
          )
      }
    }
  }

  private def runZIO[E, A](effect: ZIO[Any, E, A]): Future[A] =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.runToFuture(effect.mapError(error => FiberFailure(Cause.fail(error))))
    }
}
