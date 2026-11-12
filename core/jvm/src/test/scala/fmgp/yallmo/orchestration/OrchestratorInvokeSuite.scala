package fmgp.yallmo.orchestration

import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition}

import fmgp.yallmo.model.tool.ScalamodeToolDefinition

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, ProviderContinuation, ProviderId}
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import munit.FunSuite
import zio.*

final class OrchestratorInvokeSuite extends FunSuite {
  private val human = AgentId("human")
  private val assistant = AgentId("assistant")
  private val input = Message(MessageData(human, None, Instant.EPOCH, Message.User("hello")))

  for filesystem <- List(false, true) do {
    val backend = if filesystem then "filesystem" else "memory"

    test(
      s"$backend: invocation saves exact input/final answer independently of sessions and private intermediate output"
    ) {
      directory { root =>
        run(for {
          live <- open(root, filesystem)
          existing = SessionId.unsafe("unrelated")
          _ <- live.createSession(existing, Chunk(human))
          previous <- live.append(existing, human, Chunk(Message.User("must not become context")))
          execution = program { context =>
            for {
              saved <- live.listMessages
              _ <- ZIO.succeed {
                assert(saved.contains(input))
                assertEquals(context.messages, Chunk[MessagePayload](input.payload))
              }
            } yield AgentTurnResult(
              AgentResult(assistant, "answer"),
              context
                .append(
                  MessagePayload.Reasoning(None, ProviderContinuation(ProviderId.unsafe("private"), None, "secret"))
                )
                .append(Message.Assistant("answer"))
            )
          }
          result <- live.invoke(input, execution)
          messages <- live.listMessages
          unchanged <- live.loadSession(existing)
          reopened <- if filesystem then open(root, true).flatMap(_.listMessages) else live.listMessages
        } yield {
          assertEquals(result.payload, Message.Assistant("answer"))
          assertEquals(result.agentId, assistant)
          assertEquals(result.parentId, Some(input.id))
          assertEquals(result.id, MessageId.from(result.data))
          assertEquals(messages.map(_.id).toSet, (previous.messages :+ input :+ result).map(_.id).toSet)
          assertEquals(reopened, messages)
          assertEquals(unchanged, previous)
          assert(!messages.exists(_.payload.isInstanceOf[MessagePayload.Reasoning]))
        })
      }
    }

    test(s"$backend: registered blob uploads can finish after input acceptance but before agent execution") {
      directory { root =>
        run(for {
          live <- open(root, filesystem)
          blobId = BlobId.fresh()
          audio = Message(input.data.copy(payload = MessagePayload.AudioAttachment(blobId, AudioFormat.Wav)))
          upload <- live.beginBlobUpload(blobId, 3)
          called <- Ref.make(false)
          execution = program(context =>
            called.set(true) *> live.readBlob(blobId).map { blob =>
              assertEquals(blob.bytes, Chunk[Byte](1, 2, 3))
              assertEquals(context.messages, Chunk[MessagePayload](audio.payload))
              answered(context)
            }
          )
          fiber <- live.invoke(audio, execution).fork
          _ <- live.listMessages.repeatUntil(_.exists(_.id == audio.id))
          _ <- upload.append(0, Chunk[Byte](1))
          before <- called.get
          _ <- upload.append(1, Chunk[Byte](2, 3))
          _ <- upload.complete
          response <- fiber.join
          after <- called.get
          sessions <- live.listSessions
        } yield {
          assert(!before)
          assert(after)
          assertEquals(response.parentId, Some(audio.id))
          assertEquals(sessions, Chunk.empty)
        })
      }
    }

    test(s"$backend: unknown or aborted blob retains accepted input and never starts execution") {
      directory { root =>
        run(for {
          live <- open(root, filesystem)
          called <- Ref.make(false)
          execution = program(context => called.set(true).as(answered(context)))
          missingId = BlobId.fresh()
          missing = Message(input.data.copy(payload = MessagePayload.AudioAttachment(missingId, AudioFormat.Wav)))
          unknown <- live.invoke(missing, execution).either
          id = BlobId.fresh()
          audio = Message(input.data.copy(payload = MessagePayload.AudioAttachment(id, AudioFormat.Wav)))
          upload <- live.beginBlobUpload(id, 1)
          pending <- live.invoke(audio, execution).either.fork
          _ <- live.listMessages.repeatUntil(_.exists(_.id == audio.id))
          // Observe the dependency wait after input publication.
          _ <- (ZIO.yieldNow *> pending.status).repeatUntil(_.isInstanceOf[Fiber.Status.Suspended])
          _ <- upload.abort
          aborted <- pending.join
          count <- called.get
          stored <- live.listMessages
          sessions <- live.listSessions
        } yield {
          assertEquals(unknown, Left(YallmoError.BlobMissing(missingId)))
          // The dependency may be captured before Abort (typed failure) or afterward (missing); neither may execute.
          assert(
            aborted == Left(YallmoError.BlobUploadFailed(BlobUploadFailure.Aborted)) ||
              aborted == Left(YallmoError.BlobMissing(id))
          )
          assert(!count)
          assertEquals(stored.map(_.id).toSet, Set(missing.id, audio.id))
          assertEquals(sessions, Chunk.empty)
        })
      }
    }

    test(s"$backend: invalid input and unregistered participants cannot accept a message") {
      directory { root =>
        run(for {
          live <- open(root, filesystem)
          execution = program(_ => ZIO.dieMessage("Invalid invocation must not execute"))
          invalid = Chunk(
            input.copy(id = MessageId.sha256("wrong")),
            Message(input.data.copy(parentId = Some(input.id))),
            Message(input.data.copy(agentId = AgentId("unknown")))
          )
          results <- ZIO.foreach(invalid)(live.invoke(_, execution).either)
          unknown = new AgentInvocation[Any] {
            val agentId = AgentId("unknown")
            def invoke(input: MessagePayload): IO[YallmoError, MessagePayload] = ZIO.never
          }
          invalidAgent <- live.invoke(input, unknown).either
          stored <- live.listMessages
          sessions <- live.listSessions
        } yield {
          assert(results.forall(_.isLeft))
          assertEquals(invalidAgent, Left(YallmoError.UnknownAgent(AgentId("unknown"))))
          assertEquals(stored, Chunk.empty)
          assertEquals(sessions, Chunk.empty)
        })
      }
    }

    test(s"$backend: payload-flexible recipients preserve input, parent and exact non-text responses") {
      directory { root =>
        run(for {
          live <- open(root, filesystem)
          parent <- live.invoke(input, program(context => ZIO.succeed(answered(context))))
          payloads = Chunk[MessagePayload](
            Message.Assistant("forwarded answer"),
            Message.Assistant(""),
            Message.Instructions("submitted instructions"),
            Message.Context("context", "forwarded data"),
            Message.ToolCall(
              FunctionCall(FunctionCallId.unsafe("call"), ScalamodeToolDefinition.Execute, """{"code":"42"}""")
            ),
            Message.ToolOutput(
              FunctionGenericOutput(FunctionCallId.unsafe("previous"), FunctionKey.unsafe("other"), "previous output")
            )
          )
          results <- ZIO.foreach(payloads) { payload =>
            val request = Message(input.data.copy(parentId = Some(parent.id), payload = payload))
            val recipient = new AgentInvocation[Any] {
              val agentId = assistant
              def invoke(received: MessagePayload): IO[YallmoError, MessagePayload] =
                ZIO.succeed {
                  assertEquals(received, payload)
                  received
                }
            }
            live.invoke(request, recipient).map(result => (request, result))
          }
          stored <- live.listMessages
          sessions <- live.listSessions
          reopened <- if filesystem then open(root, true).flatMap(_.listMessages) else live.listMessages
        } yield {
          results.foreach { case (request, response) =>
            assertEquals(response.parentId, Some(request.id))
            assertEquals(response.agentId, assistant)
            assertEquals(response.payload, request.payload)
            assert(stored.contains(request) && stored.contains(response))
          }
          assertEquals(reopened, stored)
          assertEquals(sessions, Chunk.empty)
        })
      }
    }

    test(s"$backend: failure and interruption preserve input but do not fabricate a response") {
      directory { root =>
        run(for {
          live <- open(root, filesystem)
          failed <- live.invoke(input, program(_ => ZIO.fail(YallmoError.InvalidAgentDecision("failed")))).either
          started <- Promise.make[Nothing, Unit]
          running <- live.invoke(input, program(_ => started.succeed(()) *> ZIO.never)).fork
          _ <- started.await
          _ <- running.interrupt
          stored <- live.listMessages
          sessions <- live.listSessions
        } yield {
          assert(failed.isLeft)
          assertEquals(stored, Chunk(input))
          assertEquals(sessions, Chunk.empty)
        })
      }
    }

    test(s"$backend: a single-message invocation cannot execute model-requested actions") {
      directory { root =>
        run(for {
          live <- open(root, filesystem)
          calls <- Ref.make(0)
          tool = new AgentFunction[Any] {
            val definition = FunctionToolDefinition(
              FunctionKey.unsafe("probe"),
              "Must not execute"
            )
            def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
              calls.update(_ + 1).as(ExternalFunctionResult("unexpected"))
          }
          agent = new AgentInstance[Any] {
            val id = assistant
            def next(task: AgentTask, context: StaticContext[Any], current: RunContext): IO[YallmoError, AgentTurn] =
              ZIO.succeed(
                AgentTurn(
                  None,
                  AgentDecision.Actions(
                    Chunk(
                      LlmAction.CallExternalFunction(ExternalFunctionCall(tool.definition.name, "{}"))
                    )
                  )
                )
              )
          }
          invocation = new AgentRunner[Any](Map(tool.definition.name -> tool), Map.empty)
            .invocation(agent, StaticContext[Any]("Test", functions = Chunk(tool.definition)))
          result <- live.invoke(input, invocation).either
          executed <- calls.get
          stored <- live.listMessages
        } yield {
          assertEquals(result, Left(YallmoError.InvalidAgentDecision("Agent actions require Orchestrator.submit")))
          assertEquals(executed, 0)
          assertEquals(stored, Chunk(input))
        })
      }
    }

    test(s"$backend: single-message invocations reject a blank final answer") {
      directory { root =>
        run(for {
          live <- open(root, filesystem)
          result <- live
            .invoke(
              input,
              program(context => ZIO.succeed(answered(context).copy(result = AgentResult(assistant, " \n "))))
            )
            .either
          stored <- live.listMessages
        } yield {
          assertEquals(
            result,
            Left(YallmoError.InvalidAgentDecision("Agent invocation must return a non-blank answer"))
          )
          assertEquals(stored, Chunk(input))
        })
      }
    }

  }

  test("a failed input persistence never starts execution") {
    directory { root =>
      run(for {
        live <- open(root, true)
        _ <- ZIO.attempt {
          Files.delete(root.resolve("objects.jsonl")); Files.createDirectory(root.resolve("objects.jsonl"))
        }
        called <- Ref.make(false)
        result <- live.invoke(input, program(context => called.set(true).as(answered(context)))).either
        count <- called.get
      } yield {
        assert(result.swap.toOption.exists(_.isInstanceOf[YallmoError.StorageFailed]))
        assert(!count)
      })
    }
  }

  test("a failed response persistence is not reported as successful execution") {
    directory { root =>
      run(for {
        live <- open(root, true)
        result <- live
          .invoke(
            input,
            program(context =>
              live.listMessages.flatMap { saved =>
                ZIO
                  .attempt {
                    assert(saved.contains(input))
                    Files.delete(root.resolve("objects.jsonl"))
                    Files.createDirectory(root.resolve("objects.jsonl"))
                  }
                  .mapError(error => YallmoError.StorageFailure("test fault", error))
                  .as(answered(context))
              }
            )
          )
          .either
      } yield assert(result.swap.toOption.exists(_.isInstanceOf[YallmoError.StorageFailed])))
    }
  }

  test("reusing an input is not an execution replay ledger or a new session") {
    directory { root =>
      run(for {
        live <- open(root, false)
        calls <- Ref.make(0)
        execution = program(context => calls.update(_ + 1).as(answered(context)))
        _ <- live.invoke(input, execution)
        _ <- live.invoke(input, execution)
        count <- calls.get
        stored <- live.listMessages
        sessions <- live.listSessions
      } yield {
        assertEquals(count, 2)
        assertEquals(stored.count(_.id == input.id), 1)
        assertEquals(sessions, Chunk.empty)
      })
    }
  }

  test("standalone message persistence is idempotent, validates parent references, and never creates a session") {
    directory { root =>
      run(for {
        live <- open(root, true)
        child = Message(
          input.data.copy(parentId = Some(input.id), agentId = assistant, payload = Message.Assistant("reply"))
        )
        orphan <- live.storage.putMessage(child).either
        _ <- live.storage.putMessage(input)
        _ <- live.storage.putMessage(child)
        _ <- live.storage.putMessage(child)
        badHash <- live.storage.putMessage(child.copy(id = input.id)).either
        reopened <- open(root, true)
        messages <- reopened.listMessages
        sessions <- reopened.listSessions
      } yield {
        assert(orphan.isLeft)
        assert(badHash.isLeft)
        assertEquals(messages.map(_.id).toSet, Set(input.id, child.id))
        assertEquals(messages.size, 2)
        assertEquals(sessions, Chunk.empty)
      })
    }
  }

  private def program(runContext: RunContext => IO[YallmoError, AgentTurnResult]): AgentInvocation[Any] =
    new AgentRunner[Any](Map.empty, Map.empty).invocation(
      new AgentInstance[Any] {
        val id = assistant
        def next(task: AgentTask, context: StaticContext[Any], current: RunContext): IO[YallmoError, AgentTurn] = {
          assertEquals(task.prompt, current.messages.head.contentStr)
          runContext(current).map(turn =>
            AgentTurn(
              None,
              AgentDecision.FinalAnswer(turn.result.text),
              turn.runContext.messages.drop(current.messages.size)
            )
          )
        }
      },
      StaticContext[Any]("Test")
    )

  private def answered(context: RunContext): AgentTurnResult =
    AgentTurnResult(AgentResult(assistant, "answer"), context.append(Message.Assistant("answer")))

  private def open(root: Path, filesystem: Boolean): IO[YallmoError, OrchestratorLive[Any]] =
    for {
      registry <- AgentCatalog.fromAgents(Chunk(new Agent[Any] {
        val id = assistant
        def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
          ZIO.dieMessage("Use supplied execution")
      }))
      _ <- registry.registerUser(human)
      live <- if filesystem then OrchestratorLive.filesystem(root, registry) else OrchestratorLive.inMemory(registry)
    } yield live

  private def run[A](effect: ZIO[Any, Any, A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(effect.timeoutFail(new AssertionError("Invocation test timed out"))(10.seconds))
      .getOrThrowFiberFailure()
  }

  private def directory(test: Path => Unit): Unit = {
    val root = Files.createTempDirectory("yallmo-invoke-")
    try test(root)
    finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }
}
