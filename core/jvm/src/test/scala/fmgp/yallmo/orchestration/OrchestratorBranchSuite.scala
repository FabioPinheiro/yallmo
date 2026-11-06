package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.storage.{StorageFS, StorageInMemory}
import java.nio.file.Files
import java.time.Instant
import java.util.Comparator
import munit.FunSuite
import zio.*

final class OrchestratorBranchSuite extends FunSuite {
  private val human = AgentId("human")
  private val assistant = AgentId("assistant")
  private val sessionId = SessionId.unsafe("original")
  private val rootMessage = Message(
    MessageData(human, None, Instant.parse("2026-01-01T00:00:00Z"), MessagePayload.User("ancestry"))
  )
  private val oldChild = Message(
    MessageData(
      assistant,
      Some(rootMessage.id),
      Instant.parse("2026-01-01T00:00:01Z"),
      MessagePayload.Assistant("old descendant")
    )
  )
  private val context = StaticContext[Any]("Test")
  private val key = ToolKey.unsafe("probe")

  for backend <- List("memory", "filesystem") do {
    for failure <- List("none", "execution", "defect", "timeout", "generated", "prompt-before", "prompt-after") do
      test(s"$backend: branch $failure has exact parent, complete context, stored audit and no session operations") {
        withStorage(backend) { (base, reopen) =>
          for {
            _ <- base.createSession(sessionId, Chunk(human, assistant))
            before <- base.putMessagesAndUpdateSession(sessionId, None, Chunk(rootMessage, oldChild))
            calls <- Ref.make(0)
            tools <- Ref.make(0)
            storage = new NoSessions(base) {
              override def putMessage(message: Message): IO[YallmoError, Message] =
                if message.agentId == human && failure == "prompt-before" then ZIO.fail(problem)
                else if message.agentId == human && failure == "prompt-after" then
                  base.putMessage(message) *> ZIO.fail(problem)
                else if message.agentId == assistant && failure == "generated" then ZIO.fail(problem)
                else base.putMessage(message)
            }
            orchestrator <- make(storage)
            tool = new AgentTool[Any] {
              val definition = AgentToolDefinition(key, "Synthetic test tool")
              def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] = {
                val _ = call
                tools.update(_ + 1).as(ExternalToolResult("tool output"))
              }
            }
            agent = new AgentInstance[Any] {
              val id = assistant
              def next(
                  task: AgentTask,
                  context: StaticContext[Any],
                  current: RunContext
              ): IO[YallmoError, AgentTurn] = {
                val _ = context
                calls.update(
                  _ + 1
                ) *> (if current.messages.exists(_.isInstanceOf[MessagePayload.ToolOutput]) then
                        if failure == "execution" then ZIO.fail(problem)
                        else if failure == "defect" then ZIO.dieMessage("Synthetic defect")
                        else if failure == "timeout" then ZIO.never
                        else
                          ZIO.succeed(
                            AgentTurn(Some(MessagePayload.Assistant("answer")), AgentDecision.FinalAnswer("answer"))
                          )
                      else
                        for {
                          inventory <- base.listMessages
                          _ <- ZIO.succeed {
                            assertEquals(task.prompt, "branch prompt")
                            assertEquals(
                              current.messages,
                              Chunk(rootMessage.payload, MessagePayload.User("branch prompt"))
                            )
                            val prompt = inventory.find(_.payload == MessagePayload.User("branch prompt")).get
                            assertEquals(prompt.parentId, Some(rootMessage.id))
                            assertEquals(prompt.agentId, human)
                          }
                        } yield AgentTurn(
                          Some(MessagePayload.Assistant("using tool")),
                          AgentDecision.Actions(Chunk(LlmAction.CallExternalTool(ExternalToolCall(key, "{}"))))
                        ))
              }
            }
            outcome <- orchestrator
              .submitBranch(
                rootMessage.id,
                human,
                AgentTask("branch prompt"),
                new AgentRunner[Any](Map(key -> tool), Map.empty).execution(
                  agent,
                  context.copy(tools = Chunk(tool.definition)),
                  AgentBudget.default.copy(timeout = 150.millis)
                )
              )
              .either
            restored <- reopen
            after <- restored.loadSession(sessionId)
            sessions <- restored.listSessions
            inventory <- restored.listMessages
            count <- calls.get
            toolCount <- tools.get
          } yield {
            assertEquals(after, before)
            assertEquals(sessions.map(_.id), Chunk(sessionId))
            assert(inventory.contains(oldChild))
            if failure.startsWith("prompt-") then {
              val failed = outcome.swap.toOption.get
              assertEquals(failed.acceptance, BranchAcceptance.Unknown)
              assert(failed.promptId.nonEmpty)
              assertEquals(count, 0)
              assertEquals(toolCount, 0)
              assertEquals(inventory.size, if failure == "prompt-after" then 3 else 2)
            } else {
              val saved = outcome.fold(_.newMessages, _.newMessages)
              assertEquals(saved.head.parentId, Some(rootMessage.id))
              assertEquals(saved.head.agentId, human)
              assertEquals(saved.count(_.payload == MessagePayload.User("branch prompt")), 1)
              saved.foreach(message => assert(inventory.contains(message)))
              assertEquals(saved.drop(1).map(_.parentId), saved.dropRight(1).map(m => Some(m.id)))
              if failure == "none" then {
                val completed = outcome.toOption.get
                assertEquals(completed.head, saved.last.id)
                assertEquals(completed.promptId, saved.head.id)
                assertEquals(saved.last.payload, MessagePayload.Assistant("answer"))
                assertEquals(toolCount, 1)
              } else {
                val failed = outcome.swap.toOption.get
                assertEquals(failed.acceptance, BranchAcceptance.Accepted)
                assert(inventory.exists(m => failed.head.contains(m.id)))
                if failure == "generated" then assertEquals(failed.newMessages.size, 1)
                else assertEquals(toolCount, 1)
              }
            }
          }
        }
      }

    test(s"$backend: standalone root needs no session, and invalid ancestry/author/prompt reject before writes") {
      withStorage(backend) { (base, reopen) =>
        for {
          _ <- base.putMessage(rootMessage)
          calls <- Ref.make(0)
          agent = new AgentInstance[Any] {
            val id = assistant
            def next(task: AgentTask, context: StaticContext[Any], current: RunContext) = {
              val _ = (task, context, current)
              calls.update(_ + 1).as(AgentTurn(Some(MessagePayload.Assistant("ok")), AgentDecision.FinalAnswer("ok")))
            }
          }
          execution = new AgentRunner[Any](Map.empty, Map.empty).execution(agent, context)
          orchestrator <- make(new NoSessions(base))
          rejected <- ZIO.foreach(
            List(
              (MessageId.sha256("absent"), human, "no"),
              (rootMessage.id, AgentId("unknown"), "no"),
              (rootMessage.id, human, "  ")
            )
          ) { case (parent, author, prompt) =>
            orchestrator.submitBranch(parent, author, AgentTask(prompt), execution).either
          }
          before <- base.listMessages
          incomplete = Message(
            MessageData(
              assistant,
              Some(rootMessage.id),
              Instant.EPOCH,
              MessagePayload.ToolCall(FunctionCall(FunctionCallId.unsafe("unfinished"), key, "{}"))
            )
          )
          _ <- base.putMessage(incomplete)
          unfinished <- orchestrator.submitBranch(incomplete.id, human, AgentTask("no"), execution).either
          bad = rootMessage.copy(data = rootMessage.data.copy(parentId = Some(MessageId.sha256("missing"))))
          invalid <- make(new NoSessions(base) { override def listMessages = ZIO.succeed(Chunk(bad)) })
          corrupt <- invalid.submitBranch(rootMessage.id, human, AgentTask("no"), execution).either
          countBefore <- calls.get
          turn <- orchestrator.submitBranch(rootMessage.id, human, AgentTask("yes"), execution)
          restored <- reopen
          sessions <- restored.listSessions
          inventory <- restored.listMessages
        } yield {
          rejected.foreach(value => assertEquals(value.swap.toOption.get.acceptance, BranchAcceptance.NotAccepted))
          assertEquals(corrupt.swap.toOption.get.acceptance, BranchAcceptance.NotAccepted)
          assertEquals(unfinished.swap.toOption.get.acceptance, BranchAcceptance.NotAccepted)
          assertEquals(before, Chunk(rootMessage))
          assertEquals(countBefore, 0)
          assert(sessions.isEmpty)
          assert(inventory.exists(_.id == turn.head))
        }
      }
    }
  }

  private val problem = YallmoError.InvalidMessage("synthetic failure")
  private def make(storage: Storage): IO[YallmoError, OrchestratorLive[Any]] = for {
    registry <- RuntimeInstances.fromAgents(Chunk(new Agent[Any] {
      val id = assistant
      def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = {
        val _ = request
        ZIO.dieMessage("Only runner execution may run")
      }
    }))
    _ <- registry.registerUser(human)
    orchestrator <- OrchestratorLive.make(storage, registry)
  } yield orchestrator

  private def withStorage(backend: String)(test: (Storage, IO[YallmoError, Storage]) => IO[Any, Unit]): Unit = {
    val root = Files.createTempDirectory("yallmo-branch-")
    try
      Unsafe.unsafe { unsafe ?=>
        Runtime.default.unsafe
          .run((if backend == "memory" then StorageInMemory.make else StorageFS.open(root)).flatMap { base =>
            test(base, if backend == "memory" then ZIO.succeed(base) else StorageFS.open(root))
          })
          .getOrThrowFiberFailure()
      }
    finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  private class NoSessions(base: Storage) extends Storage {
    def listMessages = base.listMessages
    def putMessage(message: Message) = base.putMessage(message)
    def listBlobIds = base.listBlobIds
    def putBlob(blob: Blob) = base.putBlob(blob)
    def getBlob(id: BlobId) = base.getBlob(id)
    def readBlob(id: BlobId, timeout: Duration) = base.readBlob(id, timeout)
    def beginBlobUpload(id: BlobId, size: Long) = base.beginBlobUpload(id, size)
    private def forbidden = ZIO.dieMessage("Session operation forbidden in branch execution")
    def listSessions = forbidden
    def createSession(id: SessionId, agents: Chunk[AgentId]) = { val _ = (id, agents); forbidden }
    def loadSession(id: SessionId) = { val _ = id; forbidden }
    def loadSessionParcial(id: SessionId) = { val _ = id; forbidden }
    def addSessionAgent(id: SessionId, agent: AgentId) = { val _ = (id, agent); forbidden }
    def putMessagesAndUpdateSession(id: SessionId, head: Option[MessageId], messages: Chunk[Message]) = {
      val _ = (id, head, messages); forbidden
    }
    def compareAndSetSessionHead(id: SessionId, expected: Option[MessageId], head: Option[MessageId]) = {
      val _ = (id, expected, head); forbidden
    }
    def fork(source: SessionId, target: SessionId) = { val _ = (source, target); forbidden }
  }
}
