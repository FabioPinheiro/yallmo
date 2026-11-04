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

  test("application code can rebase immutable messages without a session or range/result wrappers") {
    val errors = typeCheckErrors("""
      import fmgp.yallmo.orchestration.*
      import fmgp.yallmo.model.*
      import zio.*
      def rebase(orchestrator: Orchestrator[Any], base: MessageId, head: MessageId, onto: Option[MessageId])
          : IO[YallmoError, Chunk[Message]] = orchestrator.rebase(base, head, onto)
    """)
    val managed = typeCheckErrors("""
      import fmgp.yallmo.orchestration.*
      import fmgp.yallmo.model.*
      def rebase(history: ManagedHistory, base: MessageId, head: MessageId) = history.rebase(base, head, None)
    """)
    assertEquals(errors, Nil)
    assertEquals(managed, Nil)
  }

  test("application code cannot execute a loop directly, even with managed history") {
    val errors = typeCheckErrors("""
      import fmgp.yallmo.orchestration.*
      def bypass(execution: AgentExecution[Any], history: ManagedHistory) =
        execution.run(AgentTask("hello"), history)
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
        def run(task: AgentTask, history: ManagedHistory) =
          ZIO.succeed(AgentTurnResult(AgentResult(agentId, "answer"), RunContext()))
      }
    """)
    assert(errors.exists(error => error.message.contains("constructor") && error.message.contains("access")))
  }

  test("programs can implement the history trait and read identities but cannot call managed append") {
    val read = typeCheckErrors("""
      import fmgp.yallmo.orchestration.*
      def inspect(history: ManagedHistory) = history.messages.map(_.map(_.id))
    """)
    val mutate = typeCheckErrors("""
      import fmgp.yallmo.orchestration.*
      def mutate(history: ManagedHistory) = history.append(zio.Chunk.empty)
    """)
    val construct = typeCheckErrors("""
      import fmgp.yallmo.orchestration.*
      import zio.*
      new ManagedHistory {
        def rebase(base: fmgp.yallmo.model.MessageId, head: fmgp.yallmo.model.MessageId, onto: Option[fmgp.yallmo.model.MessageId]) = ZIO.succeed(Chunk.empty)
        def snapshot = ZIO.succeed(Chunk.empty)
        def appendAt(payloads: Chunk[fmgp.yallmo.model.MessagePayload], expectedParent: Option[fmgp.yallmo.model.MessageId]) = ZIO.succeed(expectedParent)
        def messages = ZIO.succeed(Chunk.empty)
        def initialMessages = ZIO.succeed(Chunk.empty)
        def append(payloads: Chunk[fmgp.yallmo.model.MessagePayload]) = ZIO.unit
      }
    """)
    assertEquals(read, Nil)
    assert(mutate.exists(error => error.message.contains("append") && error.message.contains("access")))
    assertEquals(construct, Nil)
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
