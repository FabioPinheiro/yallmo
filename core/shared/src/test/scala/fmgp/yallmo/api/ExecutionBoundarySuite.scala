package fmgp.yallmo.api

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** These checks deliberately live outside the trusted orchestration package. */
final class ExecutionBoundarySuite extends FunSuite {
  test("application code can pass configured handles to the orchestrator") {
    val errors = typeCheckErrors("""
      import fmgp.yallmo.orchestration.*
      import fmgp.yallmo.model.*
      def submit(orchestrator: Orchestrator[Any], runner: AgentRunner[Any], agent: AgentInstance[Any]) =
        orchestrator.submit(
          SessionId.unsafe("test"), AgentId("human"), AgentTask("hello"),
          runner.execution(agent, StaticContext[Any]("Test"))
        )
      def invoke(orchestrator: Orchestrator[Any], runner: AgentRunner[Any], agent: AgentInstance[Any], input: Message) =
        orchestrator.invoke(input, runner.invocation(agent, StaticContext[Any]("Test")))
    """)
    assertEquals(errors, Nil)
  }

  test("application code cannot execute a loop directly, even with a no-op save callback") {
    val errors = typeCheckErrors("""
      import fmgp.yallmo.orchestration.*
      def bypass(execution: AgentExecution[Any]) =
        execution.run(AgentTask("hello"), RunContext(), _ => zio.ZIO.unit)
    """)
    assert(errors.exists(error => error.message.contains("run") && error.message.contains("access")))
  }

  test("application code cannot construct an execution that ignores persistence") {
    val errors = typeCheckErrors("""
      import fmgp.yallmo.orchestration.*
      import fmgp.yallmo.model.*
      import zio.*
      new AgentExecution[Any] {
        val agentId = AgentId("bypass")
        def run(task: AgentTask, context: RunContext, save: RunContext => UIO[Unit]) =
          save(context).as(AgentTurnResult(AgentResult(agentId, "answer"), context))
      }
    """)
    assert(errors.exists(error => error.message.contains("constructor") && error.message.contains("access")))
  }

  test("the runner has no public callback-free loop entry points") {
    val direct = typeCheckErrors("""
      import fmgp.yallmo.orchestration.*
      def bypass(runner: AgentRunner[Any], agent: AgentInstance[Any]) =
        runner.run(agent, AgentTask("hello"), StaticContext[Any]("Test"))
    """)
    val contextual = typeCheckErrors("""
      import fmgp.yallmo.orchestration.*
      def bypass(runner: AgentRunner[Any], agent: AgentInstance[Any]) =
        runner.runWithContext(agent, AgentTask("hello"), StaticContext[Any]("Test"), RunContext())
    """)
    assert(direct.exists(_.message.contains("run")))
    assert(contextual.exists(_.message.contains("runWithContext")))
  }

  test("single-message recipients must also execute through their persistence boundary") {
    val errors = typeCheckErrors("""
      import fmgp.yallmo.orchestration.*
      import fmgp.yallmo.model.*
      def bypass(invocation: AgentInvocation[Any]) = invocation.invoke(MessagePayload.User("hello"))
    """)
    assert(errors.exists(error => error.message.contains("invoke") && error.message.contains("access")))
  }

  test("a loop handle cannot be invoked as a standalone message recipient") {
    val errors = typeCheckErrors("""
      import fmgp.yallmo.orchestration.*
      def bypass(execution: AgentExecution[Any]): AgentInvocation[Any] = execution
    """)
    assert(errors.nonEmpty)
  }
}
