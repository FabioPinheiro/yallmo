package fmgp.yallmo.server.application

import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition}

import fmgp.yallmo.model.tool.{FilesystemToolDefinition, ScalamodeToolDefinition}

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, LlmStopReason}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{AgentCapabilities, HasAgentCapabilities}
import fmgp.rpc.RpcWebSocket
import fmgp.yallmo.fs.{FsRead, FsError}
import fmgp.yallmo.tool.{ReadFileTool, ScalaCodeExecutor, ScalaTool}
import munit.FunSuite
import zio.*

import scala.concurrent.Future

final class ConfiguredProgramSuite extends FunSuite {
  private val agentId = AgentId("configured")
  private val blob = Blob(BlobId.fresh(), Chunk[Byte](1, 2, 3))
  private val input = Chunk[MessagePayload](
    MessagePayload.User("Review this"),
    MessagePayload.AudioAttachment(blob.id, AudioFormat.Wav)
  )
  private val context = StaticContext[Any](
    "System",
    functions =
      Chunk("shared", "program-only", "adapter-only").map(key => FunctionToolDefinition(FunctionKey.unsafe(key), key))
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
            AgentCapabilities(functions = Some(Set(FunctionKey.unsafe("shared"), FunctionKey.unsafe("adapter-only"))))
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
          capabilities =
            AgentCapabilities(functions = Some(Set(FunctionKey.unsafe("shared"), FunctionKey.unsafe("program-only"))))
        )
        bound = program.bind[Any](adapter, readBlob)
        configured <- bound.configuredContext(context)
        turn <- bound.next(AgentTask("Review this"), context, RunContext(input))
        request <- requests.get.map(_.head)
        stored <- request.readBlob(blob.id)
      } yield {
        assertEquals(bound.id, agentId)
        assertEquals(bound.capabilities.functions, Some(Set(FunctionKey.unsafe("shared"))))
        assertEquals(configured.functions.map(_.name), Chunk(FunctionKey.unsafe("shared")))
        assertEquals(request.tools.map(_.name), Chunk(FunctionKey.unsafe("shared")))
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
        bound = FolderAgentProgram(adapter, policy, readBlob)
        configured <- bound.configuredContext(context)
        turn <- bound.next(AgentTask("Review this"), context, RunContext(input))
        request <- requests.get.map(_.head)
        stored <- request.readBlob(blob.id)
      } yield {
        assertEquals(bound.id, agentId)
        assertEquals(bound.capabilities, policy.copy(tools = Some(Set.empty)))
        assertEquals(configured.functions, Chunk.empty)
        assertEquals(request.model, "")
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
          ): Task[ExternalFunctionResult.ScalaMode] =
            used.set(true).as(ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed("unexpected")))
          override def executeFilesystem(
              code: String,
              endpoint: String,
              timeout: Duration,
              maxOutputBytes: Int
          ): ZIO[FsRead & RpcWebSocket.RpcRoute, Throwable, ExternalFunctionResult.ScalaMode] =
            used.set(true).as(ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed("unexpected")))
        }
        route <- RpcWebSocket.route()
        scalaTool = ScalaTool.filesystem("ws://127.0.0.1:1/rpc")
        tools = Chunk[AgentFunction[Any]](
          ApplicationTools.bindFilesystem(new ReadFileTool, fs),
          new AgentFunction[Any] {
            val definition = scalaTool.definition
            def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
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
          List(
            AgentCapabilities(),
            AgentCapabilities(functions = Some(Set(ScalamodeToolDefinition.Execute, FilesystemToolDefinition.ReadFile)))
          )
        ) { policy =>
          val binding = FolderAgentProgram(adapter, policy, readBlob)
          val execution = new AgentRunner[Any](tools.map(tool => tool.definition.name -> tool).toMap, Map.empty)
            .invocation(binding, context.copy(functions = tools.map(_.definition)))
          for {
            registry <- AgentCatalog.fromAgents(Chunk(adapter))
            orchestrator <- OrchestratorLive.inMemory(registry)
            invoke = (payload: MessagePayload) =>
              orchestrator
                .invoke(
                  Message(MessageData(agentId, None, java.time.Instant.EPOCH, payload)),
                  execution
                )
                .map(_.payload)
            _ <- ZIO.foreachDiscard(
              List(
                ScalamodeToolDefinition.Execute -> """{"code":"fs.readUtf8(\"notes.txt\")","mode":"filesystem"}""",
                FilesystemToolDefinition.ReadFile -> """{"path":"notes.txt"}"""
              )
            ) { (key, arguments) =>
              invoke(MessagePayload.ToolCall(FunctionCall(FunctionCallId.unsafe("denied"), key, arguments)))
                .map {
                  case MessagePayload.ToolOutput(output) =>
                    assertEquals(output.name, key)
                    assertEquals(ujson.read(output.output)("error")("kind").str, "ToolUnavailableToAgent")
                  case other => fail(s"Expected denied tool output, got $other")
                }
            }
            answer <- invoke(MessagePayload.User("show folder"))
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
        FolderAgentProgram(adapter, AgentCapabilities(), readBlob)
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
