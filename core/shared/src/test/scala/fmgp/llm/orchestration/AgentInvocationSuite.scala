package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import munit.FunSuite
import scala.concurrent.Future
import zio.*

final class AgentInvocationSuite extends FunSuite {
  private val id = AgentId("recipient")
  private val call = FunctionCall(FunctionCallId.unsafe("direct-call"), ToolKey.unsafe("echo"), "{}")

  private def agent: AgentInstance[Any] = new AgentInstance[Any] {
    val id: AgentId = AgentInvocationSuite.this.id
    def next(task: AgentTask, context: StaticContext[Any], current: RunContext): IO[YallmoError, AgentTurn] =
      ZIO.succeed(AgentTurn(Some(Message.Assistant("handled")), AgentDecision.FinalAnswer("handled")))
  }

  test("historical calls and supplied ToolOutput data do not execute tools") {
    run(for {
      calls <- Ref.make(0)
      tool = new AgentTool[Any] {
        val definition = AgentToolDefinition(ToolKey.unsafe("echo"), "test")
        def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
          calls.update(_ + 1).as(ExternalToolResult("output"))
      }
      runner = new AgentRunner[Any](Map(ToolKey.unsafe("echo") -> tool), Map.empty)
      context = StaticContext[Any]("", tools = Chunk(tool.definition))
      _ <- runner.runWithContext(agent, AgentTask("continue"), context, RunContext(Chunk(Message.ToolCall(call))))
      reply <- runner
        .execution(agent, context)
        .invoke(Message.ToolOutput(FunctionGenericOutput(call.id, call.name, "data")))
      count <- calls.get
    } yield {
      assertEquals(count, 0)
      assertEquals(reply, Message.Assistant("handled"))
    })
  }

  test("a direct call is bounded and interrupts its tool without asking the agent for a turn") {
    run(for {
      cancelled <- Ref.make(false)
      tool = new AgentTool[Any] {
        val definition = AgentToolDefinition(ToolKey.unsafe("echo"), "test")
        def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
          ZIO.never.onInterrupt(cancelled.set(true))
      }
      neverInfer = new AgentInstance[Any] {
        val id: AgentId = AgentInvocationSuite.this.id
        def next(task: AgentTask, context: StaticContext[Any], current: RunContext): IO[YallmoError, AgentTurn] =
          ZIO.dieMessage("Direct tools must not invoke the agent")
      }
      runner = new AgentRunner[Any](Map(ToolKey.unsafe("echo") -> tool), Map.empty)
      context = StaticContext[Any]("", tools = Chunk(tool.definition))
      result <- runner
        .execution(neverInfer, context, AgentBudget(1, 1, 50.millis))
        .invoke(Message.ToolCall(call))
        .either
      interrupted <- cancelled.get
    } yield {
      assertEquals(result, Left(YallmoError.AgentTimedOut(50.millis)))
      assert(interrupted)
    })
  }

  test("invalid budgets prevent direct execution") {
    run(for {
      runner = new AgentRunner[Any](Map.empty, Map.empty)
      result <- runner
        .execution(agent, StaticContext[Any](""), AgentBudget(1, 0, 1.second))
        .invoke(Message.ToolCall(call))
        .either
    } yield assert(result.left.toOption.exists(_.isInstanceOf[YallmoError.InvalidAgentBudget])))
  }

  private def run[A](effect: Task[A]): Future[A] =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.runToFuture(effect) }
}
