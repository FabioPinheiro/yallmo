package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import java.nio.file.{Files, Path}
import java.util.Comparator
import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import zio.*

final class OrchestratorSubmitSuite extends FunSuite {
  test("the core orchestrator flushes the prompt before execution and persists only the response delta") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(userId, assistantId))
          _ <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("earlier")))
          _ <- orchestrator.append(sessionId, assistantId, Chunk(MessagePayload.Assistant("previous answer")))
          expected = Chunk(
            MessagePayload.User("earlier"),
            MessagePayload.Assistant("previous answer"),
            MessagePayload.User("now")
          )
          program = new AgentInstance[Any] {
            override val id: AgentId = assistantId
            override def configuredContext(context: StaticContext[Any]): IO[YallmoError, StaticContext[Any]] =
              ZIO.succeed {
                assertEquals(Files.readAllLines(root.resolve("objects.jsonl")).size(), 3)
                context
              }
            override def next(
                task: AgentTask,
                context: StaticContext[Any],
                current: RunContext
            ): IO[YallmoError, AgentTurn] =
              ZIO.succeed {
                assertEquals(current.messages, expected)
                AgentTurn(Some(MessagePayload.Assistant("answer")), AgentDecision.FinalAnswer("answer"))
              }
          }
          turn <- orchestrator.submit(sessionId, userId, AgentTask("now"), runner.execution(program, context))
          reopened <- open(root)
          stored <- reopened.loadSession(sessionId)
        } yield {
          assertEquals(turn.session, stored)
          assertEquals(stored.messages.map(_.payload), expected :+ MessagePayload.Assistant("answer"))
          assertEquals(stored.messages.map(_.agentId), Chunk(userId, assistantId, userId, assistantId))
          assertEquals(turn.newMessages, Chunk(MessagePayload.User("now"), MessagePayload.Assistant("answer")))
          assertEquals(Files.readAllLines(root.resolve("objects.jsonl")).size(), 4)
        }
      }
    }
  }

  test("a real runner timeout leaves the accepted prompt available in the error and on disk") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(userId))
          execution = runner.execution(program(_ => ZIO.never), context, AgentBudget.default.copy(timeout = 25.millis))
          failed <- orchestrator.submit(sessionId, userId, AgentTask("keep me"), execution).either
          reopened <- open(root)
          stored <- reopened.loadSession(sessionId)
        } yield {
          val failure = failed.swap.toOption.get
          assert(failure.error.isInstanceOf[YallmoError.AgentTimedOut])
          assertEquals(failure.acceptedSession, Some(stored))
          assertEquals(stored.messages.map(_.payload), Chunk(MessagePayload.User("keep me")))
          assertEquals(stored.agentIds, Chunk(userId))
        }
      }
    }
  }

  test("a failed prompt write prevents the agent loop from starting") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(userId))
          called <- Ref.make(false)
          _ <- ZIO.attempt {
            Files.delete(root.resolve("objects.jsonl"))
            Files.createDirectory(root.resolve("objects.jsonl"))
          }
          execution = runner.execution(program(_ => called.set(true).as(answer)), context)
          failed <- orchestrator.submit(sessionId, userId, AgentTask("cannot save"), execution).either
          wasCalled <- called.get
        } yield {
          assert(!wasCalled)
          assertEquals(failed.swap.toOption.get.acceptedSession, None)
          assert(failed.swap.toOption.get.error.isInstanceOf[YallmoError.StorageFailed])
        }
      }
    }
  }

  test("interrupting execution does not remove the prompt that the orchestrator already saved") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(userId))
          started <- Promise.make[Nothing, Unit]
          execution = runner.execution(program(_ => started.succeed(()) *> ZIO.never), context)
          fiber <- orchestrator.submit(sessionId, userId, AgentTask("cancelled"), execution).fork
          _ <- started.await
          _ <- fiber.interrupt
          stored <- orchestrator.loadSession(sessionId)
        } yield assertEquals(stored.messages.map(_.payload), Chunk(MessagePayload.User("cancelled")))
      }
    }
  }

  test("unregistered execution is rejected before accepting the prompt") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(userId))
          invalid = new AgentExecution[Any] {
            override val agentId: AgentId = AgentId("unknown")
            override private[orchestration] def run(
                task: AgentTask,
                history: ManagedHistory
            ): IO[YallmoError, AgentTurnResult] =
              ZIO.dieMessage("Unknown execution must not run")
          }
          failed <- orchestrator.submit(sessionId, userId, AgentTask("reject"), invalid).either
          stored <- orchestrator.loadSession(sessionId)
        } yield {
          assertEquals(failed, Left(SessionSubmitFailure(YallmoError.UnknownAgent(AgentId("unknown")), None)))
          assertEquals(stored.messages, Chunk.empty)
        }
      }
    }
  }

  test("a registered user cannot execute a turn or accept a prompt as an assistant") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(userId))
          invalid = new AgentExecution[Any] {
            override val agentId: AgentId = userId
            override private[orchestration] def run(
                task: AgentTask,
                history: ManagedHistory
            ): IO[YallmoError, AgentTurnResult] =
              ZIO.dieMessage("A user has no executable agent")
          }
          failed <- orchestrator.submit(sessionId, userId, AgentTask("reject"), invalid).either
          stored <- orchestrator.loadSession(sessionId)
        } yield {
          assertEquals(failed, Left(SessionSubmitFailure(YallmoError.UnknownAgent(userId), None)))
          assertEquals(stored.messages, Chunk.empty)
        }
      }
    }
  }

  test("an execution cannot change its response author") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(userId))
          invalid = new AgentExecution[Any] {
            override val agentId: AgentId = assistantId
            override private[orchestration] def run(
                task: AgentTask,
                history: ManagedHistory
            ): IO[YallmoError, AgentTurnResult] =
              ZIO.succeed(AgentTurnResult(AgentResult(userId, "bad"), RunContext()))
          }
          failed <- orchestrator.submit(sessionId, userId, AgentTask("preserve"), invalid).either
          stored <- orchestrator.loadSession(sessionId)
        } yield {
          assert(failed.swap.toOption.get.error.isInstanceOf[YallmoError.InvalidAgentDecision])
          assertEquals(stored.messages.map(_.payload), Chunk(MessagePayload.User("preserve")))
        }
      }
    }
  }

  test("an empty response still returns the saved prompt without an empty append") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(userId))
          execution = new AgentExecution[Any] {
            override val agentId: AgentId = assistantId
            override private[orchestration] def run(
                task: AgentTask,
                history: ManagedHistory
            ): IO[YallmoError, AgentTurnResult] =
              ZIO.succeed(AgentTurnResult(AgentResult(assistantId, "done"), RunContext()))
          }
          result <- orchestrator.submit(sessionId, userId, AgentTask("no response message"), execution)
        } yield assertEquals(result.newMessages, result.session.messages.map(_.payload))
      }
    }
  }

  test("checkpoints save calls during tool execution and each output before the next tool finishes") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(userId))
          tool = new AgentTool[Any] {
            val definition = AgentToolDefinition(ToolKey.unsafe("probe"), "Check persistence")
            def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
              awaitStored(orchestrator) { messages =>
                val calls = messages.count(_.isInstanceOf[MessagePayload.ToolCall])
                val outputs = messages.count(_.isInstanceOf[MessagePayload.ToolOutput])
                calls == 2 && outputs == (if call.argumentsJson == "{}" then 0 else 1)
              }.as(ExternalToolResult("saved"))
          }
          configuredRunner = new AgentRunner[Any](Map(tool.definition.key -> tool), Map.empty)
          configuredContext = context.copy(tools = Chunk(tool.definition))
          execution = configuredRunner.execution(twoCalls(tool.definition.key), configuredContext)
          turn <- orchestrator.submit(sessionId, userId, AgentTask("tools"), execution)
          reopened <- open(root)
          stored <- reopened.loadSession(sessionId)
        } yield {
          val payloads = stored.messages.map(_.payload)
          assertEquals(
            payloads.map(_.getClass.getSimpleName),
            Chunk("User", "Assistant", "ToolCall", "ToolCall", "ToolOutput", "ToolOutput", "Assistant")
          )
          val calls = payloads.collect { case MessagePayload.ToolCall(call) => call }
          val outputs = payloads.collect { case MessagePayload.ToolOutput(output) => output }
          assertEquals(calls.map(_.id), outputs.map(_.callId))
          assertEquals(calls.map(_.id).distinct.size, 2)
          assertEquals(turn.session, stored)
          assertEquals(turn.newMessages, payloads)
          assertEquals(Files.readAllLines(root.resolve("objects.jsonl")).size(), payloads.size)
        }
      }
    }
  }

  test("interrupting a later tool drains checkpoints and retains the earlier tool output") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(userId))
          started <- Promise.make[Nothing, Unit]
          tool = new AgentTool[Any] {
            val definition = AgentToolDefinition(ToolKey.unsafe("probe"), "Two tools")
            def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
              if call.argumentsJson == "{}" then ZIO.succeed(ExternalToolResult("first result"))
              else started.succeed(()) *> ZIO.never
          }
          execution = new AgentRunner[Any](Map(tool.definition.key -> tool), Map.empty)
            .execution(twoCalls(tool.definition.key), context.copy(tools = Chunk(tool.definition)))
          fiber <- orchestrator.submit(sessionId, userId, AgentTask("cancel tools"), execution).fork
          _ <- started.await
          _ <- fiber.interrupt
          reopened <- open(root)
          stored <- reopened.loadSession(sessionId)
        } yield {
          assertEquals(stored.messages.count(_.payload.isInstanceOf[MessagePayload.ToolCall]), 2)
          assertEquals(stored.messages.count(_.payload.isInstanceOf[MessagePayload.ToolOutput]), 1)
          assertEquals(stored.messages.last.payload.contentStr, "first result")
        }
      }
    }
  }

  test("an inference failure exposes the latest saved tool checkpoints in the submission error") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(userId))
          tool = new AgentTool[Any] {
            val definition = AgentToolDefinition(ToolKey.unsafe("probe"), "Successful tool")
            def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
              ZIO.succeed(ExternalToolResult(call.argumentsJson))
          }
          base = twoCalls(tool.definition.key)
          failing = program { current =>
            if current.messages.exists(_.isInstanceOf[MessagePayload.ToolOutput]) then
              ZIO.fail(YallmoError.InvalidConfig("inference failed"))
            else base.next(AgentTask("fail"), context, current)
          }
          execution = new AgentRunner[Any](Map(tool.definition.key -> tool), Map.empty)
            .execution(failing, context.copy(tools = Chunk(tool.definition)))
          failed <- orchestrator.submit(sessionId, userId, AgentTask("fail"), execution).either
          stored <- orchestrator.loadSession(sessionId)
        } yield {
          val failure = failed.swap.toOption.get
          assertEquals(failure.error, YallmoError.InvalidConfig("inference failed"))
          assertEquals(failure.acceptedSession, Some(stored))
          assertEquals(stored.messages.size, 6)
          assertEquals(stored.messages.count(_.payload.isInstanceOf[MessagePayload.ToolOutput]), 2)
        }
      }
    }
  }

  test("failed checkpoint writes log errors and warn about all unsaved generated messages without failing execution") {
    withRoot { root =>
      val logs = new CopyOnWriteArrayList[String]()
      run {
        withLogs(logs) {
          for {
            orchestrator <- open(root)
            _ <- orchestrator.createSession(sessionId, Chunk(userId))
            execution = runner.execution(
              program { _ =>
                ZIO.succeed {
                  Files.move(root.resolve("objects.jsonl"), root.resolve("objects.backup"))
                  Files.createDirectory(root.resolve("objects.jsonl"))
                  AgentTurn(
                    Some(MessagePayload.Assistant("answer despite storage failure")),
                    AgentDecision.FinalAnswer("answer despite storage failure"),
                    Chunk(MessagePayload.Context("extra", "context"))
                  )
                }
              },
              context
            )
            turn <- orchestrator.submit(sessionId, userId, AgentTask("continue"), execution)
          } yield {
            assertEquals(turn.result.text, "answer despite storage failure")
            assertEquals(turn.session.messages.map(_.payload), Chunk(MessagePayload.User("continue")))
            assertEquals(turn.newMessages.size, 3)
            assert(
              logs.asScala.exists(line =>
                line.contains("level=ERROR") && line.contains("checkpoint persistence failed")
              )
            )
            assert(
              logs.asScala.exists(line =>
                line.contains("level=WARN") && line.contains("2 generated message(s) not stored")
              )
            )
          }
        }
      }
    }
  }

  test("tools continue after a checkpoint error and later snapshots retry the unsaved suffix") {
    withRoot { root =>
      val logs = new CopyOnWriteArrayList[String]()
      run {
        withLogs(logs) {
          for {
            orchestrator <- open(root)
            _ <- orchestrator.createSession(sessionId, Chunk(userId))
            tool = new AgentTool[Any] {
              val definition = AgentToolDefinition(ToolKey.unsafe("probe"), "Recover storage")
              def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
                if call.argumentsJson != "{}" then ZIO.succeed(ExternalToolResult("second"))
                else
                  (ZIO.yieldNow *> ZIO.succeed(logs.asScala.exists(_.contains("checkpoint persistence failed"))))
                    .repeatUntil(identity)
                    .timeoutFail(YallmoError.InvalidConfig("Checkpoint did not run during the tool"))(5.seconds) *>
                    ZIO.succeed {
                      Files.delete(root.resolve("objects.jsonl"))
                      Files.move(root.resolve("objects.backup"), root.resolve("objects.jsonl"))
                      ExternalToolResult("recovered")
                    }
            }
            base = twoCalls(tool.definition.key)
            recovering = program { current =>
              if !current.messages.exists(_.isInstanceOf[MessagePayload.ToolOutput]) then
                ZIO.succeed {
                  Files.move(root.resolve("objects.jsonl"), root.resolve("objects.backup"))
                  Files.createDirectory(root.resolve("objects.jsonl"))
                } *> base.next(AgentTask("recover"), context, current)
              else ZIO.succeed(answer)
            }
            execution = new AgentRunner[Any](Map(tool.definition.key -> tool), Map.empty)
              .execution(recovering, context.copy(tools = Chunk(tool.definition)))
            turn <- orchestrator.submit(sessionId, userId, AgentTask("recover"), execution)
            reopened <- open(root)
            stored <- reopened.loadSession(sessionId)
          } yield {
            assertEquals(turn.session, stored)
            assertEquals(turn.newMessages, stored.messages.map(_.payload))
            assertEquals(stored.messages.size, 7)
            assert(logs.asScala.exists(_.contains("checkpoint persistence failed")))
            assert(!logs.asScala.exists(_.contains("generated message(s) not stored")))
          }
        }
      }
    }
  }

  private def twoCalls(key: ToolKey): AgentInstance[Any] =
    program { current =>
      if current.messages.exists(_.isInstanceOf[MessagePayload.ToolOutput]) then ZIO.succeed(answer)
      else
        ZIO.succeed(
          AgentTurn(
            Some(MessagePayload.Assistant("using tools")),
            AgentDecision.Actions(
              Chunk(
                LlmAction.CallExternalTool(ExternalToolCall(key, "{}")),
                LlmAction.CallExternalTool(ExternalToolCall(key, """{"second":true}"""))
              )
            )
          )
        )
    }

  private def awaitStored(orchestrator: Orchestrator[Any])(
      ready: Chunk[MessagePayload] => Boolean
  ): IO[YallmoError, Session] =
    (ZIO.yieldNow *> orchestrator.loadSession(sessionId))
      .repeatUntil(session => ready(session.messages.map(_.payload)))
      .timeoutFail(YallmoError.InvalidConfig("Checkpoint was not saved during tool execution"))(5.seconds)

  private def withLogs[E, A](logs: CopyOnWriteArrayList[String])(effect: IO[E, A]): IO[E, A] =
    ZIO.scoped {
      val logger = ZLogger.default.map(line => { logs.add(line); () })
      (Runtime.removeDefaultLoggers >>> Runtime.addLogger(logger)).build *> effect
    }

  private val sessionId = SessionId.unsafe("main")
  private val userId = AgentId("human")
  private val assistantId = AgentId("assistant")
  private val context = StaticContext[Any]("Test")
  private def runner = new AgentRunner[Any](Map.empty, Map.empty)
  private def answer = AgentTurn(Some(MessagePayload.Assistant("ok")), AgentDecision.FinalAnswer("ok"))

  private def program(respond: RunContext => IO[YallmoError, AgentTurn]): AgentInstance[Any] =
    new AgentInstance[Any] {
      override val id: AgentId = assistantId
      override def next(task: AgentTask, context: StaticContext[Any], current: RunContext): IO[YallmoError, AgentTurn] =
        respond(current)
    }

  private def open(root: Path): IO[YallmoError, Orchestrator[Any]] = {
    def participant(agentId: AgentId): Agent[Any] = new Agent[Any] {
      override val id: AgentId = agentId
      override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
        ZIO.dieMessage("Only the supplied execution should be invoked")
    }
    for {
      instances <- RuntimeInstances.fromAgents(Chunk(participant(assistantId)))
      _ <- instances.registerUser(userId)
      orchestrator <- OrchestratorLive.filesystem(root, instances)
    } yield orchestrator
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def withRoot(test: Path => Unit): Unit = {
    val root = Files.createTempDirectory("yallmo-orchestrator-submit-")
    try test(root)
    finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }
}
