package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.som.*
import zio.json.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, ProviderContinuation, ProviderId}
import fmgp.yallmo.orchestration.storage.{StorageFS, StorageInMemory}
import java.nio.file.{Files, Path}
import java.util.Comparator
import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import zio.*

final class ManagedHistorySubmitSuite extends FunSuite {
  private val sessionId = SessionId.unsafe("managed")
  private val userId = AgentId("human")
  private val agentId = AgentId("assistant")
  private val toolKey = ToolKey.unsafe("probe")
  private val context = StaticContext[Any]("Managed history")
  private val finalTurn = AgentTurn(Some(MessagePayload.Assistant("done")), AgentDecision.FinalAnswer("done"))
  private val failure = YallmoError.InvalidConfig("injected storage failure")

  for backend <- List("memory", "filesystem") do {
    for scenario <- List(
        "keep",
        "rebase",
        "low-confidence",
        "provider-failure",
        "malformed",
        "no-candidate",
        "missing-base",
        "competing-pointer"
      )
    do
      test(s"$backend: opt-in research-note System One $scenario then normal native execution") {
        val logs = new CopyOnWriteArrayList[String]()
        withStorage(backend) { storage =>
          withLogs(logs) {
            for {
              orchestrator <- make(storage)
              seed <- orchestrator.append(
                sessionId,
                userId,
                Chunk(
                  MessagePayload.User("research request"),
                  reasoningPayload,
                  MessagePayload.Context("temporary-research", "temporary raw research note"),
                  MessagePayload.Context("research-summary", "verified final research summary")
                )
              )
              requests <- Ref.make(Chunk.empty[AgentTurnRequest])
              advisory <- Ref.make(Chunk.empty[String])
              captured <- Promise.make[Nothing, ManagedHistory]
              competitor <- Ref.make(Option.empty[Session])
              conversational = new Agent[Any] {
                val id = agentId
                def turn(request: AgentTurnRequest) = requests
                  .update(_ :+ request)
                  .as(
                    AgentTurnResponse(
                      Chunk(MessagePayload.Assistant("research complete")),
                      fmgp.yallmo.model.llm.LlmStopReason.Stop
                    )
                  )
              }
              native = NativeFunctionAgent[Agent[Any]](agentId, "recording-fake").bind[Any](conversational)
              advisor = new SOMProvider {
                protected def evaluateRequestJson(json: String) =
                  ZIO.succeed(assertHistoryDecisionWire(json)) *> advisory.update(_ :+ json) *>
                    orchestrator
                      .append(sessionId, userId, Chunk(MessagePayload.User("competitor")))
                      .flatMap(saved => competitor.set(Some(saved)))
                      .when(scenario == "competing-pointer") *>
                    (if scenario == "provider-failure" then ZIO.fail(YallmoError.ProtocolFailure("SECRET-ERROR"))
                     else if scenario == "malformed" then
                       ZIO.fromEither(
                         "{bad-json".fromJson[SystemOneResponse].left.map(YallmoError.ProtocolFailure.apply)
                       )
                     else
                       ZIO.succeed(
                         historyDecisionResponse(
                           if scenario == "keep" then "keep" else "rebase",
                           if scenario == "low-confidence" then 0.2 else 0.98
                         )
                       ))
              }
              agent = new AgentInstance[Any] {
                val id = agentId
                override val capabilities = native.capabilities
                override def configuredContext(context: StaticContext[Any]) = native.configuredContext(context)
                def next(task: AgentTask, context: StaticContext[Any], current: RunContext) =
                  native.next(task, context, current)
                override def nextManaged(
                    task: AgentTask,
                    context: StaticContext[Any],
                    current: RunContext,
                    history: ManagedHistory
                ) =
                  for {
                    _ <- captured.succeed(history)
                    fresh <- SystemOneHistoryDecision("jev-latest")
                      .prepare(history) { active =>
                        if scenario == "no-candidate" then None
                        else
                          active
                            .find(_.payload match {
                              case MessagePayload.Context("temporary-research", _) => true
                              case _                                               => false
                            })
                            .map(note =>
                              (
                                if scenario == "missing-base" then MessageId.unsafe("missing") else note.id,
                                active.last.id,
                                Some(active.head.id),
                                "A temporary research note has been superseded by a verified final summary; retain the summary and current task."
                              )
                            )
                      }
                      .provideEnvironment(ZEnvironment[SOMProvider](advisor))
                    result <- native.nextManaged(task, context, fresh, history)
                  } yield result
              }
              result <- orchestrator.submit(
                sessionId,
                userId,
                AgentTask("finish research"),
                new AgentRunner[Any](Map.empty, Map.empty).execution(agent, context)
              )
              history <- captured.await
              active <- history.messages
              seen <- requests.get
              wire <- advisory.get
              competing <- competitor.get
              reopened <- reload(storage)
              inventory <- reopened.listMessages
              saved <- reopened.loadSession(sessionId)
            } yield {
              val applied = scenario == "rebase" || scenario == "competing-pointer"
              assertEquals(seen.size, 1)
              assertEquals(seen.head.tools, Chunk.empty)
              assertEquals(
                seen.head.input,
                active.dropRight(1).map(_.payload) :+ MessagePayload.Instructions(context.systemPrompt)
              )
              assertEquals(wire.size, if Set("no-candidate", "missing-base")(scenario) then 0 else 1)
              wire.foreach { json =>
                val request = json.fromJson[SystemOneRequest[HistoryDecisionEvidence]].toOption.get
                assertEquals(request.questions.keySet, Set("rewrite"))
                assertEquals(
                  request.questions("rewrite").asInstanceOf[SystemOneQuestion.Choice].criteria.keySet,
                  Set("keep", "rebase")
                )
                assert(!json.contains("SECRET"))
                assert(!json.contains("temporary raw research note"))
                assert(!json.contains("sha256:"))
                assertEquals(request.model, "jev-latest")
                assert(request.state.context.length < 200)
              }
              assertEquals(active.exists(_.payload == seed.messages(2).payload), !applied)
              assertEquals(active.exists(_.payload.isInstanceOf[MessagePayload.Reasoning]), !applied)
              assert(active.exists(_.payload == seed.messages.last.payload))
              assertEquals(active.last.agentId, agentId)
              assertEquals(active.last.payload, MessagePayload.Assistant("research complete"))
              assertEquals(active.drop(1).map(_.parentId), active.dropRight(1).map(m => Some(m.id)))
              assert(seed.messages.forall(inventory.contains))
              assertEquals(saved.messages, competing.fold(active)(_.messages))
              assert(!result.newMessages.exists(_.contentStr.contains("preselected context rebase")))
              assert(!active.exists(_.payload.contentStr.contains("superseded by a verified")))
              if scenario == "competing-pointer" then
                assert(
                  logs.asScala.exists(line =>
                    line.contains("level=ERROR") && line.contains("pointer publication failed")
                  )
                )
              assert(!logs.asScala.exists(line => line.contains("SECRET") || line.contains("superseded by a verified")))
            }
          }
        }
      }

    for change <- List("append", "at-apply-append", "restored", "at-apply-restored", "cancel") do
      test(s"$backend: advisory $change uses exact captured head identity") {
        withStorage(backend) { storage =>
          for {
            orchestrator <- make(storage)
            _ <- orchestrator.append(
              sessionId,
              userId,
              Chunk(MessagePayload.User("seed"), MessagePayload.Context("temporary", "note"))
            )
            captured <- Promise.make[Nothing, ManagedHistory]
            reached <- Promise.make[Nothing, Unit]
            release <- Promise.make[Nothing, Unit]
            seen <- Ref.make(0)
            advisor = new SOMProvider {
              protected def evaluateRequestJson(json: String) =
                ZIO.succeed(assertHistoryDecisionWire(json)) *>
                  (reached.succeed(()) *> release.await).unless(change.startsWith("at-apply")) *>
                  ZIO.succeed(historyDecisionResponse())
            }
            agent = new AgentInstance[Any] {
              val id = agentId
              def next(task: AgentTask, context: StaticContext[Any], current: RunContext) =
                ZIO.dieMessage("managed only")
              override def nextManaged(
                  task: AgentTask,
                  context: StaticContext[Any],
                  current: RunContext,
                  history: ManagedHistory
              ) = {
                val gated = new ManagedHistory {
                  def initialMessages = history.initialMessages
                  def messages = history.messages
                  def rebase(base: MessageId, head: MessageId, onto: Option[MessageId]) =
                    (reached.succeed(()) *> release.await).when(change.startsWith("at-apply")) *>
                      history.rebase(base, head, onto)
                  private[orchestration] def snapshot = history.snapshot
                  private[orchestration] def append(payloads: Chunk[MessagePayload]) = history.append(payloads)
                  private[orchestration] def appendAt(
                      payloads: Chunk[MessagePayload],
                      expectedParent: Option[MessageId]
                  ) = history.appendAt(payloads, expectedParent)

                }
                for {
                  _ <- captured.succeed(history)
                  _ <- SystemOneHistoryDecision("jev-latest")
                    .prepare(gated)(active =>
                      Some((active(1).id, active.last.id, Some(active.head.id), "Drop a superseded temporary note."))
                    )
                    .provideEnvironment(ZEnvironment[SOMProvider](advisor))
                  _ <- seen.update(_ + 1)
                } yield finalTurn
              }
            }
            running <- orchestrator
              .submit(
                sessionId,
                userId,
                AgentTask("continue"),
                new AgentRunner[Any](Map.empty, Map.empty).execution(agent, context)
              )
              .fork
            _ <- reached.await
            history <- captured.await
            original <- history.messages
            _ <-
              if change.endsWith("append") then history.append(Chunk(MessagePayload.Context("concurrent", "keep")))
              else if change.endsWith("restored") then
                history.rebase(original.last.id, original.last.id, Some(original.head.id)) *>
                  history.rebase(original.head.id, original.head.id, Some(original.last.id)).unit
              else ZIO.unit
            beforeRelease <- history.messages
            _ <- ZIO.succeed(assertEquals(beforeRelease, original)).when(change.endsWith("restored"))
            result <- if change == "cancel" then running.interrupt else release.succeed(()) *> running.await
            after <- history.messages
            continuations <- seen.get
            late <- history.rebase(original.head.id, original.last.id, None).exit
          } yield {
            val restored = change.endsWith("restored")
            assertEquals(result.isSuccess, restored)
            if change == "cancel" then assert(result.isInterrupted)
            assertEquals(continuations, if restored then 1 else 0)
            if restored then {
              assertEquals(
                after.map(_.payload),
                Chunk(original.head.payload, original.last.payload, finalTurn.assistant.get)
              )
              assert(!after.exists(_.payload == original(1).payload))
              assertEquals(after.drop(1).map(_.parentId), after.dropRight(1).map(m => Some(m.id)))
            } else assertEquals(after, beforeRelease)
            assertEquals(after.exists(_.payload == finalTurn.assistant.get), restored)
            assert(late.isFailure) // Capability lifetime remains submission-scoped.
          }
        }
      }

    for fault <- List("object-save", "incomplete-pair") do
      test(s"$backend: chosen advisory rebase $fault propagates, never reports applied or continues") {
        withStorage(backend) { storage =>
          for {
            fail <- Ref.make(false)
            probe = new StorageProbe(storage) {
              override def putMessage(message: Message) = fail.get.flatMap { enabled =>
                if enabled && message.payload == MessagePayload.User("continue") && message.parentId.isEmpty then
                  ZIO.fail(failure)
                else super.putMessage(message)
              }
            }
            orchestrator <- make(probe)
            _ <- orchestrator.append(
              sessionId,
              userId,
              if fault == "incomplete-pair" then
                Chunk(MessagePayload.User("seed"), callPayload("cut"), outputPayload("cut"))
              else Chunk(MessagePayload.User("seed"), MessagePayload.Context("temporary", "note"))
            )
            captured <- Promise.make[Nothing, ManagedHistory]
            before <- Ref.make(Chunk.empty[Message])
            seen <- Ref.make(0)
            advisor = new SOMProvider {
              protected def evaluateRequestJson(json: String) =
                ZIO.succeed(assertHistoryDecisionWire(json)) *> fail.set(true).as(historyDecisionResponse())
            }
            agent = new AgentInstance[Any] {
              val id = agentId
              def next(task: AgentTask, context: StaticContext[Any], current: RunContext) =
                ZIO.dieMessage("managed only")
              override def nextManaged(
                  task: AgentTask,
                  context: StaticContext[Any],
                  current: RunContext,
                  history: ManagedHistory
              ) =
                for {
                  _ <- captured.succeed(history)
                  active <- history.messages
                  _ <- before.set(active)
                  _ <- SystemOneHistoryDecision("jev-latest")
                    .prepare(history)(active =>
                      Some(
                        (
                          active(1).id,
                          active.last.id,
                          if fault == "object-save" then None else Some(active.head.id),
                          "Drop temporary context."
                        )
                      )
                    )
                    .provideEnvironment(ZEnvironment[SOMProvider](advisor))
                  _ <- seen.update(_ + 1)
                } yield finalTurn
            }
            result <- orchestrator
              .submit(
                sessionId,
                userId,
                AgentTask("continue"),
                new AgentRunner[Any](Map.empty, Map.empty).execution(agent, context)
              )
              .exit
            history <- captured.await
            active <- history.messages
            original <- before.get
            count <- seen.get
          } yield {
            assert(result.isFailure)
            assertEquals(active, original)
            assertEquals(count, 0)
          }
        }
      }

    test(
      s"$backend: programs see stable accepted/call/output identities while a blocked object writer does not block tools"
    ) {
      withStorage(backend) { storage =>
        for {
          entered <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          first <- Ref.make(true)
          observed <- Ref.make(Chunk.empty[Chunk[Message]])
          captured <- Promise.make[Nothing, ManagedHistory]
          probe = new StorageProbe(storage) {
            override def putMessage(message: Message): IO[YallmoError, Message] =
              first.getAndSet(false).flatMap { block =>
                (entered.succeed(()) *> release.await).when(block) *> super.putMessage(message)
              }
          }
          orchestrator <- make(probe)
          agent = observingAgent(observed, captured)
          tool = toolWith {
            for {
              _ <- entered.await
              history <- captured.await
              active <- history.messages
              stored <- storage.listMessages
              _ <- ZIO.succeed {
                assertEquals(active.count(_.payload.isInstanceOf[MessagePayload.ToolCall]), 1)
                assertEquals(stored.size, 1) // Tool has already run while every generated object is blocked.
              }
              _ <- release.succeed(())
            } yield ExternalToolResult("result")
          }
          turn <- orchestrator.submit(sessionId, userId, AgentTask("run"), execution(agent, tool))
          history <- captured.await
          active <- history.messages
          observations <- observed.get
          stored <- storage.loadSession(sessionId)
          all <- storage.listMessages
        } yield {
          assertEquals(observations.size, 2)
          assertEquals(observations.head, stored.messages.take(1))
          assertEquals(observations.last, stored.messages.dropRight(1))
          assertEquals(active, stored.messages)
          assertEquals(all.map(_.id).toSet, active.map(_.id).toSet)
          assertEquals(turn.newMessages, active.map(_.payload))
          assertEquals(active.map(_.id).distinct.size, active.size)
          assertEquals(active.drop(1).map(_.parentId), active.dropRight(1).map(m => Some(m.id)))
        }
      }
    }

    test(s"$backend: every turn reads latest managed history and extensions, calls and each output append once") {
      withStorage(backend) { storage =>
        for {
          orchestrator <- make(storage)
          captured <- Promise.make[Nothing, ManagedHistory]
          seen <- Ref.make(Chunk.empty[Chunk[Message]])
          extensions <- Ref.make(0)
          executions <- Ref.make(0)
          extension = new ContextExtension[Any] {
            val key = "per-turn"
            def apply(context: StaticContext[Any], current: RunContext): IO[YallmoError, RunContext] =
              extensions.updateAndGet(_ + 1).map(n => current.append(MessagePayload.Context(key, s"$n")))
          }
          agent = new AgentInstance[Any] {
            val id: AgentId = agentId
            def next(task: AgentTask, context: StaticContext[Any], current: RunContext): IO[YallmoError, AgentTurn] =
              ZIO.dieMessage("Managed entry point required")
            override def nextManaged(
                task: AgentTask,
                context: StaticContext[Any],
                current: RunContext,
                history: ManagedHistory
            ): IO[YallmoError, AgentTurn] =
              for {
                _ <- captured.succeed(history)
                active <- history.messages
                observations <- seen.updateAndGet(_ :+ active)
                _ <- ZIO.succeed(assertEquals(current.messages, active.map(_.payload)))
              } yield
                if observations.size == 1 then
                  AgentTurn(
                    Some(MessagePayload.Assistant("calling")),
                    AgentDecision.Actions(Chunk("first", "second").map { name =>
                      LlmAction.CallExternalTool(ExternalToolCall(toolKey, "{}", Some(FunctionCallId.unsafe(name))))
                    }),
                    Chunk(MessagePayload.Context("generated", "additional"))
                  )
                else finalTurn
          }
          tool = toolWith {
            for {
              history <- captured.await
              count <- executions.updateAndGet(_ + 1)
              active <- history.messages
              _ <- ZIO.succeed {
                assertEquals(active.count(_.payload.isInstanceOf[MessagePayload.ToolCall]), 2)
                assertEquals(active.count(_.payload.isInstanceOf[MessagePayload.ToolOutput]), count - 1)
              }
            } yield ExternalToolResult("result")
          }
          turn <- orchestrator.submit(
            sessionId,
            userId,
            AgentTask("latest"),
            new AgentRunner[Any](Map(toolKey -> tool), Map.empty)
              .execution(agent, context.copy(tools = Chunk(tool.definition), extensions = Chunk(extension)))
          )
          history <- captured.await
          active <- history.messages
          observations <- seen.get
          stored <- storage.loadSession(sessionId)
        } yield {
          val payloads = active.map(_.payload)
          assertEquals(observations.size, 2)
          assertEquals(observations.last, active.dropRight(1))
          assertEquals(observations.last.count(_.payload.isInstanceOf[MessagePayload.ToolOutput]), 2)
          assertEquals(payloads.collect { case MessagePayload.Context("per-turn", n) => n }, Chunk("1", "2"))
          assertEquals(payloads.count(_ == MessagePayload.Context("generated", "additional")), 1)
          val calls = payloads.collect { case MessagePayload.ToolCall(call) => call.id }
          val outputs = payloads.collect { case MessagePayload.ToolOutput(output) => output.callId }
          assertEquals(calls, Chunk(FunctionCallId.unsafe("first"), FunctionCallId.unsafe("second")))
          assertEquals(outputs, calls)
          assertEquals(active, stored.messages)
          assertEquals(turn.newMessages, payloads)
          assertEquals(payloads.count(_ == MessagePayload.User("latest")), 1)
        }
      }
    }

    test(s"$backend: managed rebase refreshes nextManaged request, preserves originals and expires mutation") {
      withStorage(backend) { storage =>
        for {
          orchestrator <- make(storage)
          seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
          captured <- Promise.make[Nothing, ManagedHistory]
          before <- Ref.make(Chunk.empty[Message])
          agent = new AgentInstance[Any] {
            val id = agentId
            def next(task: AgentTask, context: StaticContext[Any], current: RunContext) = ZIO.dieMessage("managed only")
            override def nextManaged(
                task: AgentTask,
                context: StaticContext[Any],
                current: RunContext,
                history: ManagedHistory
            ) =
              for {
                _ <- captured.succeed(history)
                original <- history.messages
                _ <- before.set(original)
                copies <- history.rebase(seed.messages.head.id, original.last.id, None)
                fresh <- history.messages // Explicitly rebuild any provider request from this read.
                _ <- ZIO.succeed {
                  assertEquals(copies, fresh)
                  assertEquals(fresh.map(_.payload), Chunk(MessagePayload.User("rewrite")))
                  assertEquals(fresh.head.parentId, None)
                  assertEquals(fresh.head.timestamp, original.last.timestamp)
                  assertEquals(fresh.head.agentId, userId)
                }
              } yield finalTurn
          }
          turn <- orchestrator.submit(
            sessionId,
            userId,
            AgentTask("rewrite"),
            new AgentRunner[Any](Map.empty, Map.empty).execution(agent, context)
          )
          history <- captured.await
          active <- history.messages
          originals <- before.get
          initial <- history.initialMessages
          lateAppend <- history.append(Chunk(MessagePayload.Assistant("late"))).exit
          lateRebase <- history.rebase(active.head.id, active.last.id, None).exit
          reopened <- reload(storage)
          saved <- reopened.loadSession(sessionId)
          inventory <- reopened.listMessages
        } yield {
          assertEquals(saved.messages, active)
          assertEquals(saved.agentIds.toSet, Set(userId, agentId))
          assertEquals(turn.newMessages, Chunk(MessagePayload.User("rewrite"), MessagePayload.Assistant("done")))
          assert(originals.forall(inventory.contains))
          assertEquals(initial, originals)
          assertEquals(active.last.parentId, Some(active.head.id))
          assert(lateAppend.isFailure && lateRebase.isFailure)
          assertEquals(active.size, 2)
        }
      }
    }

    for shape <- List("empty-root", "no-op", "overlap", "long-destination", "repeat") do {
      test(s"$backend: managed $shape has exact ancestry and execution audit independent of replaced prefix") {
        withStorage(backend) { storage =>
          for {
            orchestrator <- make(storage)
            seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
            destination <- ZIO.foldLeft(Chunk("d1", "d2", "d3"))(Chunk.empty[Message]) { (chain, text) =>
              Clock.instant
                .flatMap(time =>
                  storage.putMessage(
                    Message(MessageData(userId, chain.lastOption.map(_.id), time, MessagePayload.User(text)))
                  )
                )
                .map(chain :+ _)
            }
            captured <- Promise.make[Nothing, ManagedHistory]
            originals <- Ref.make(Chunk.empty[Message])
            run = new AgentExecution[Any] {
              val agentId = ManagedHistorySubmitSuite.this.agentId
              private[orchestration] def run(task: AgentTask, history: ManagedHistory) = for {
                _ <- captured.succeed(history)
                _ <- history.append(Chunk(MessagePayload.Assistant("actual")))
                current <- history.messages
                _ <- originals.set(current)
                base = if shape == "empty-root" then current.last.id else seed.messages.head.id
                onto = shape match {
                  case "no-op"            => Some(base)
                  case "overlap"          => Some(current.last.id)
                  case "long-destination" => Some(destination.last.id)
                  case _                  => None
                }
                copies <- history.rebase(base, current.last.id, onto)
                next <- history.messages
                _ <- ZIO.succeed {
                  val prefix = if shape == "no-op" then seed.messages
                  else if shape == "overlap" then current
                  else if shape == "long-destination" then destination
                  else Chunk.empty
                  assertEquals(next, prefix ++ copies)
                  if shape == "empty-root" then assertEquals(next, Chunk.empty)
                  if shape == "no-op" then assertEquals(next, current)
                }
                _ <-
                  if shape == "repeat" then history.rebase(next.head.id, next.last.id, Some(next.head.id)).unit
                  else ZIO.unit
                _ <- history.append(Chunk(MessagePayload.Assistant("after")))
              } yield AgentTurnResult(AgentResult(agentId, "after"), RunContext())
            }
            turn <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), run)
            history <- captured.await
            current <- history.messages
            old <- originals.get
            reopened <- reload(storage)
            stored <- reopened.loadSession(sessionId)
            inventory <- reopened.listMessages
          } yield {
            assertEquals(
              turn.newMessages,
              Chunk(
                MessagePayload.User("prompt"),
                MessagePayload.Assistant("actual"),
                MessagePayload.Assistant("after")
              )
            )
            assertEquals(stored.messages, current)
            assert(old.forall(inventory.contains))
            assert(current.forall(inventory.contains))
          }
        }
      }
    }

    test(s"$backend: external pointer competition logs ERROR, never invalidates rebase or later objects") {
      val logs = new CopyOnWriteArrayList[String]()
      withStorage(backend) { storage =>
        withLogs(logs) {
          for {
            orchestrator <- make(storage)
            seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
            competitor <- Ref.make(Option.empty[Session])
            captured <- Promise.make[Nothing, ManagedHistory]
            execution = new AgentExecution[Any] {
              val agentId = ManagedHistorySubmitSuite.this.agentId
              private[orchestration] def run(task: AgentTask, history: ManagedHistory) = for {
                _ <- captured.succeed(history)
                current <- history.messages
                external <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("competitor")))
                _ <- competitor.set(Some(external))
                _ <- history.rebase(seed.messages.head.id, current.last.id, None)
                _ <- history.append(Chunk(MessagePayload.Assistant("continued")))
              } yield AgentTurnResult(AgentResult(agentId, "continued"), RunContext())
            }
            turn <- orchestrator.submit(sessionId, userId, AgentTask("ours"), execution)
            history <- captured.await
            active <- history.messages
            other <- competitor.get
            reopened <- reload(storage)
            saved <- reopened.loadSession(sessionId)
            inventory <- reopened.listMessages
          } yield {
            assertEquals(saved.messages, other.get.messages)
            assert(active.forall(inventory.contains))
            assertEquals(turn.newMessages, Chunk(MessagePayload.User("ours"), MessagePayload.Assistant("continued")))
            assert(
              logs.asScala.exists(line =>
                line.contains("ERROR") && line.contains("pointer publication failed") && line.contains("old=") && line
                  .contains("new=")
              )
            )
          }
        }
      }
    }

    test(s"$backend: pending snapshots drain before rewrite; stale local source head cannot drop an append") {
      withStorage(backend) { storage =>
        for {
          entered <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          first <- Ref.make(true)
          probe = new StorageProbe(storage) {
            override def putMessage(message: Message) = first.getAndSet(false).flatMap { block =>
              (entered.succeed(()) *> release.await).when(block) *> super.putMessage(message)
            }
          }
          orchestrator <- make(probe)
          seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
          captured <- Promise.make[Nothing, ManagedHistory]
          execution = new AgentExecution[Any] {
            val agentId = ManagedHistorySubmitSuite.this.agentId
            private[orchestration] def run(task: AgentTask, history: ManagedHistory) = for {
              _ <- captured.succeed(history)
              prompt <- history.messages
              _ <- history.append(Chunk(MessagePayload.Assistant("queued")))
              _ <- entered.await
              old <- history.rebase(seed.messages.head.id, prompt.last.id, None).exit
              current <- history.messages
              rewrite <- history.rebase(seed.messages.head.id, current.last.id, None).fork
              _ <- release.succeed(())
              copies <- rewrite.join
              fresh <- history.messages
              _ <- ZIO.succeed { assert(old.isFailure); assertEquals(fresh, copies) }
              _ <- history.append(Chunk(MessagePayload.Assistant("new")))
            } yield AgentTurnResult(AgentResult(agentId, "new"), RunContext())
          }
          turn <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), execution)
          history <- captured.await
          active <- history.messages
          saved <- storage.loadSession(sessionId)
        } yield {
          assertEquals(saved.messages, active)
          assertEquals(active.map(_.payload), turn.newMessages)
          assertEquals(active.head.parentId, None)
        }
      }
    }

    test(s"$backend: stale nextManaged output retained on original branch, not reattributed to new context") {
      withStorage(backend) { storage =>
        for {
          orchestrator <- make(storage)
          seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
          captured <- Promise.make[Nothing, ManagedHistory]
          originalHead <- Ref.make(Option.empty[MessageId])
          agent = new AgentInstance[Any] {
            val id = agentId
            def next(task: AgentTask, context: StaticContext[Any], current: RunContext) = ZIO.dieMessage("managed only")
            override def nextManaged(
                task: AgentTask,
                context: StaticContext[Any],
                current: RunContext,
                history: ManagedHistory
            ) = for {
              _ <- captured.succeed(history)
              old <- history.messages
              _ <- originalHead.set(Some(old.last.id))
              _ <- history.rebase(seed.messages.head.id, old.last.id, None)
              initial <- history.initialMessages
              _ <- ZIO.succeed(assertEquals(initial, old))
              // Origin reads do not refresh the work fence: finalTurn still belongs to the old request.
            } yield finalTurn
          }
          failed <- orchestrator
            .submit(
              sessionId,
              userId,
              AgentTask("prompt"),
              new AgentRunner[Any](Map.empty, Map.empty).execution(agent, context)
            )
            .exit
          history <- captured.await
          active <- history.messages
          oldHead <- originalHead.get
          inventory <- storage.listMessages
        } yield {
          assert(failed.isFailure)
          assertEquals(active.map(_.payload), Chunk(MessagePayload.User("prompt")))
          assertEquals(inventory.find(_.payload == MessagePayload.Assistant("done")).map(_.parentId), Some(oldHead))
        }
      }
    }

    for point <- List("objects", "copy", "pointer", "post-pointer", "post-pointer-read") do {
      test(s"$backend: managed $point failure separates canonical context from pointer publication") {
        val logs = new CopyOnWriteArrayList[String]()
        withStorage(backend) { storage =>
          withLogs(logs) {
            for {
              fail <- Ref.make(true)
              pointerFailed <- Promise.make[Nothing, Unit]
              failRead <- Ref.make(false)
              probe = new StorageProbe(storage) {
                override def putMessage(message: Message) = fail.get.flatMap { enabled =>
                  if enabled && (point == "objects" && message.payload == MessagePayload.Assistant("actual") ||
                      point == "copy" && message.parentId.isEmpty && message.payload == MessagePayload.User("prompt"))
                  then ZIO.fail(failure)
                  else super.putMessage(message)
                }
                override def compareAndSetSessionHead(id: SessionId, old: Option[MessageId], next: Option[MessageId]) =
                  for {
                    enabled <- fail.get
                    inventory <- storage.listMessages
                    chain <- fmgp.yallmo.orchestration.storage.StoreageUtils
                      .ancestry(inventory.map(m => m.id -> m).toMap, next)
                    inject = enabled && chain.headOption.exists(_.payload == MessagePayload.User("prompt"))
                    saved <-
                      if inject && point == "pointer" then pointerFailed.succeed(()) *> ZIO.fail(failure)
                      else
                        super.compareAndSetSessionHead(id, old, next).flatMap { saved =>
                          if inject && point.startsWith("post-pointer") then
                            failRead.set(point == "post-pointer-read") *> pointerFailed.succeed(()) *> ZIO.fail(failure)
                          else ZIO.succeed(saved)
                        }
                  } yield saved
                override def loadSession(id: SessionId) = failRead.getAndSet(false).flatMap {
                  case true  => ZIO.fail(failure)
                  case false => super.loadSession(id)
                }
              }
              orchestrator <- make(probe)
              seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
              captured <- Promise.make[Nothing, ManagedHistory]
              execution = new AgentExecution[Any] {
                val agentId = ManagedHistorySubmitSuite.this.agentId
                private[orchestration] def run(task: AgentTask, history: ManagedHistory) = for {
                  _ <- captured.succeed(history)
                  _ <- history.append(Chunk(MessagePayload.Assistant("actual")))
                  before <- history.messages
                  result <- history.rebase(seed.messages.head.id, before.last.id, None).exit
                  after <- history.messages
                  _ <- ZIO.succeed {
                    if point == "objects" || point == "copy" then {
                      assert(result.isFailure); assertEquals(after, before)
                    } else { assert(result.isSuccess); assertEquals(after.head.parentId, None) }
                  }
                  _ <- pointerFailed.await.when(point.contains("pointer"))
                  _ <- fail.set(false)
                  _ <- history.append(Chunk(MessagePayload.Assistant("after")))
                } yield AgentTurnResult(AgentResult(agentId, "after"), RunContext())
              }
              turn <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), execution)
              history <- captured.await
              active <- history.messages
              reopened <- reload(storage)
              stored <- reopened.loadSession(sessionId)
              all <- reopened.listMessages
            } yield {
              if point.contains("pointer") then
                assert(
                  logs.asScala.exists(line =>
                    line.contains("level=ERROR") && line.contains("pointer publication failed") && line.contains(
                      "old="
                    ) && line.contains("new=")
                  )
                )
              assertEquals(stored.messages, active)
              assert(active.forall(all.contains))
              assertEquals(
                turn.newMessages,
                Chunk(
                  MessagePayload.User("prompt"),
                  MessagePayload.Assistant("actual"),
                  MessagePayload.Assistant("after")
                )
              )
            }
          }
        }
      }
    }

    for stage <- List("before-activation", "after-activation") do {
      test(s"$backend: cancellation $stage drains and closes retained capability without rollback") {
        withStorage(backend) { storage =>
          for {
            entered <- Promise.make[Nothing, Unit]
            release <- Promise.make[Nothing, Unit]
            first <- Ref.make(true)
            probe = new StorageProbe(storage) {
              override def putMessage(message: Message) =
                if stage == "before-activation" && message.parentId.isEmpty && message.payload == MessagePayload.User(
                    "prompt"
                  )
                then entered.succeed(()) *> release.await *> super.putMessage(message)
                else super.putMessage(message)
              override def compareAndSetSessionHead(id: SessionId, old: Option[MessageId], next: Option[MessageId]) =
                if stage == "after-activation" then
                  first.getAndSet(false).flatMap { block =>
                    (entered.succeed(()) *> release.await).when(block) *> super.compareAndSetSessionHead(id, old, next)
                  }
                else super.compareAndSetSessionHead(id, old, next)
            }
            orchestrator <- make(probe)
            seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
            captured <- Promise.make[Nothing, ManagedHistory]
            execution = new AgentExecution[Any] {
              val agentId = ManagedHistorySubmitSuite.this.agentId
              private[orchestration] def run(task: AgentTask, history: ManagedHistory) =
                captured.succeed(history) *> history.messages.flatMap(current =>
                  history.rebase(seed.messages.head.id, current.last.id, None)
                ) *> ZIO.never
            }
            fiber <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), execution).fork
            _ <- entered.await
            history <- captured.await
            _ <-
              if stage == "before-activation" then fiber.interrupt *> release.succeed(())
              else
                for {
                  interrupted <- fiber.interrupt.fork
                  _ <- release.succeed(())
                  _ <- interrupted.join
                } yield ()
            active <- history.messages
            expired <- history.append(Chunk(MessagePayload.Assistant("expired"))).exit
            saved <- storage.loadSession(sessionId)
            all <- storage.listMessages
          } yield {
            assert(expired.isFailure)
            assert(active.forall(all.contains))
            assertEquals(saved.messages, active)
            assertEquals(active.head.parentId, None)
            if stage == "before-activation" then assertEquals(active.head.payload, MessagePayload.User("seed"))
            else assertEquals(active.head.payload, MessagePayload.User("prompt"))
          }
        }
      }
    }

    for scenario <- List(
        "missing-result",
        "missing-call",
        "duplicate-call",
        "duplicate-output",
        "wrong-name",
        "wrong-id",
        "output-first",
        "source-cut",
        "destination-cut",
        "crossing-cut",
        "collision",
        "false-pairing"
      )
    do {
      test(s"$backend: tool validation rejects $scenario before candidate writes or activation") {
        withStorage(backend) { storage =>
          for {
            orchestrator <- make(storage)
            seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
            originals <- Ref.make(Chunk.empty[Message])
            destination <- persistChain(
              storage,
              if Set("destination-cut", "crossing-cut")(scenario) then Chunk(callPayload("one"))
              else if scenario == "collision" then Chunk(callPayload("one"), outputPayload("one"))
              else Chunk(MessagePayload.User("destination"))
            )
            execution = new AgentExecution[Any] {
              val agentId = ManagedHistorySubmitSuite.this.agentId
              private[orchestration] def run(task: AgentTask, history: ManagedHistory) = for {
                _ <- history.append(scenario match {
                  case "missing-result"   => Chunk(callPayload("one"))
                  case "missing-call"     => Chunk(outputPayload("one"))
                  case "duplicate-call"   => Chunk(callPayload("one"), callPayload("one"), outputPayload("one"))
                  case "duplicate-output" => Chunk(callPayload("one"), outputPayload("one"), outputPayload("one"))
                  case "wrong-name"       =>
                    Chunk(
                      callPayload("one"),
                      MessagePayload.ToolOutput(
                        FunctionGenericOutput(FunctionCallId.unsafe("one"), ToolKey.unsafe("different"), "result")
                      )
                    )
                  case "wrong-id"      => Chunk(callPayload("one"), outputPayload("other"))
                  case "output-first"  => Chunk(outputPayload("one"), callPayload("one"))
                  case "false-pairing" =>
                    Chunk(
                      MessagePayload.ToolCall(
                        FunctionCall(FunctionCallId.unsafe("one"), toolKey, "{\"original\":true}")
                      ),
                      outputPayload("one"),
                      MessagePayload.Context("cut", "cut"),
                      callPayload("one"),
                      outputPayload("one")
                    )
                  case _ => Chunk(callPayload("one"), outputPayload("one"))
                })
                current <- history.messages
                _ <- originals.set(current)
                base =
                  if Set("source-cut", "crossing-cut")(scenario) then current(current.size - 2).id
                  else if scenario == "false-pairing" then current(current.size - 3).id
                  else seed.messages.head.id
                rejected <- history.rebase(base, current.last.id, destination.lastOption.map(_.id)).exit
                after <- history.messages
                _ <- ZIO.succeed {
                  assert(rejected.isFailure)
                  assertEquals(after, current)
                }
              } yield AgentTurnResult(AgentResult(agentId, "done"), RunContext())
            }
            _ <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), execution)
            original <- originals.get
            reopened <- reload(storage)
            all <- reopened.listMessages
            saved <- reopened.loadSession(sessionId)
          } yield {
            assertEquals(saved.messages, original)
            assertEquals(all.map(_.id).toSet, (original ++ destination).map(_.id).toSet)
          }
        }
      }
    }

    test(
      s"$backend: blank-ID inventory overlay rejects before candidate writes without persisting undecodable objects"
    ) {
      withStorage(backend) { storage =>
        for {
          time <- Clock.instant
          call = Message(MessageData(userId, None, time, callPayload("")))
          output = Message(MessageData(userId, Some(call.id), time, outputPayload("")))
          overlay = Chunk(call, output)
          writes <- Ref.make(Chunk.empty[Message])
          probe = new StorageProbe(storage) {
            override def listMessages = super.listMessages.map(_ ++ overlay)
            override def putMessage(message: Message) = writes.update(_ :+ message) *> super.putMessage(message)
          }
          orchestrator <- make(probe)
          execution = new AgentExecution[Any] {
            val agentId = ManagedHistorySubmitSuite.this.agentId
            private[orchestration] def run(task: AgentTask, history: ManagedHistory) = for {
              current <- history.messages
              rejected <- history.rebase(current.last.id, current.last.id, Some(output.id)).exit
              after <- history.messages
              _ <- ZIO.succeed {
                assert(rejected.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[YallmoError.InvalidMessage]))
                assertEquals(after, current)
              }
            } yield AgentTurnResult(AgentResult(agentId, "done"), RunContext())
          }
          turn <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), execution)
          written <- writes.get
          reopened <- reload(storage)
          all <- reopened.listMessages
        } yield {
          assertEquals(written, Chunk.empty) // Prompt acceptance uses the batch seam, not putMessage.
          assertEquals(all, turn.session.messages)
          assert(!all.exists(m => overlay.exists(_.id == m.id)))
        }
      }
    }

    for failAt <- List("destination", "source") do {
      test(s"$backend: sanitized $failAt object save failure leaves active/pointer unchanged") {
        withStorage(backend) { storage =>
          for {
            destination <- persistChain(storage, Chunk(reasoningPayload, MessagePayload.User("destination")))
            failWrites <- Ref.make(false)
            attempted <- Ref.make(Chunk.empty[Message])
            probe = new StorageProbe(storage) {
              override def putMessage(message: Message) = failWrites.get.flatMap { enabled =>
                if enabled then
                  attempted.update(_ :+ message) *>
                    (if message.payload == MessagePayload.User(if failAt == "destination" then "destination"
                       else "prompt")
                     then ZIO.fail(failure)
                     else super.putMessage(message))
                else super.putMessage(message)
              }
            }
            orchestrator <- make(probe)
            execution = new AgentExecution[Any] {
              val agentId = ManagedHistorySubmitSuite.this.agentId
              private[orchestration] def run(task: AgentTask, history: ManagedHistory) = for {
                current <- history.messages
                _ <- failWrites.set(true)
                rejected <- history.rebase(current.head.id, current.last.id, Some(destination.last.id)).exit
                after <- history.messages
                saved <- storage.loadSession(sessionId)
                _ <- ZIO.succeed {
                  assert(rejected.isFailure)
                  assertEquals(after, current)
                  assertEquals(saved.messages, current)
                }
                _ <- failWrites.set(false)
              } yield AgentTurnResult(AgentResult(agentId, "done"), RunContext())
            }
            // Keep prompt in selected range so the source-write failure follows a saved sanitized destination.
            seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
            turn <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), execution)
            tried <- attempted.get
            reopened <- reload(storage)
            saved <- reopened.loadSession(sessionId)
            all <- reopened.listMessages
          } yield {
            assertEquals(saved.messages, turn.session.messages)
            assertEquals(saved.messages.head.id, seed.messages.head.id)
            assertEquals(tried.size, if failAt == "destination" then 1 else 2)
            assert(tried.forall(m => !m.payload.isInstanceOf[MessagePayload.Reasoning]))
            assert(destination.forall(all.contains))
            if failAt == "source" then assert(all.contains(tried.head)) // Partial immutable prefix is permitted.
          }
        }
      }
    }

    for reasoningOnly <- List(false, true) do {
      test(
        s"$backend: sanitized destination and source have valid hashes, provenance and suffix (reasoning-only=$reasoningOnly)"
      ) {
        val logs = new CopyOnWriteArrayList[String]()
        withStorage(backend) { storage =>
          withLogs(logs) {
            for {
              orchestrator <- make(storage)
              seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
              destination <- persistChain(
                storage,
                Chunk(
                  MessagePayload.User("destination"),
                  reasoningPayload,
                  callPayload("destination"),
                  outputPayload("destination"),
                  reasoningPayload
                )
              )
              captured <- Promise.make[Nothing, ManagedHistory]
              before <- Ref.make(Chunk.empty[Message])
              copies <- Ref.make(Chunk.empty[Message])
              execution = new AgentExecution[Any] {
                val agentId = ManagedHistorySubmitSuite.this.agentId
                private[orchestration] def run(task: AgentTask, history: ManagedHistory) = for {
                  _ <- captured.succeed(history)
                  prompt <- history.messages
                  _ <- history.append(if reasoningOnly then Chunk(reasoningPayload)
                  else
                    Chunk(
                      reasoningPayload,
                      MessagePayload.ToolCall(
                        FunctionCall(
                          FunctionCallId.unsafe("first"),
                          toolKey,
                          "{\"kept\":true}",
                          Some("kept-provider-item")
                        )
                      ),
                      callPayload("second"),
                      outputPayload("second"),
                      reasoningPayload,
                      outputPayload("first"),
                      reasoningPayload
                    ))
                  original <- history.messages
                  _ <- before.set(original)
                  base = if reasoningOnly then prompt.last.id else seed.messages.head.id
                  noop <- history.rebase(base, original.last.id, Some(base))
                  unchanged <- history.messages
                  _ <- ZIO.succeed {
                    assertEquals(noop, original.drop(original.indexWhere(_.id == base) + 1))
                    assertEquals(unchanged, original) // Exact no-op retains even opaque continuation bytes/IDs.
                  }
                  rewritten <- history.rebase(base, original.last.id, Some(destination.last.id))
                  _ <- copies.set(rewritten)
                  refreshed <- history.messages
                  _ <- ZIO.succeed {
                    assertEquals(
                      rewritten,
                      refreshed.drop(destination.count(m => !m.payload.isInstanceOf[MessagePayload.Reasoning]))
                    )
                    assert(!refreshed.exists(_.payload.isInstanceOf[MessagePayload.Reasoning]))
                    assertEquals(rewritten.size, if reasoningOnly then 0 else 5)
                  }
                } yield AgentTurnResult(AgentResult(agentId, "done"), RunContext())
              }
              turn <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), execution)
              history <- captured.await
              active <- history.messages
              original <- before.get
              rewritten <- copies.get
              reopened <- reload(storage)
              saved <- reopened.loadSession(sessionId)
              all <- reopened.listMessages
            } yield {
              assertEquals(saved.messages, active)
              assertEquals(turn.session.messages, active)
              assert((original ++ destination).forall(all.contains))
              assertEquals(active.head.parentId, None)
              assertEquals(active.drop(1).map(_.parentId), active.dropRight(1).map(m => Some(m.id)))
              assert(active.forall(m => m.id == MessageId.from(m.data)))
              val retained = destination.filterNot(_.payload.isInstanceOf[MessagePayload.Reasoning]) ++
                (if reasoningOnly then Chunk.empty
                 else original.drop(1).filterNot(_.payload.isInstanceOf[MessagePayload.Reasoning]))
              assertEquals(active.map(_.data.copy(parentId = None)), retained.map(_.data.copy(parentId = None)))
              assertEquals(
                rewritten.map(_.payload),
                if reasoningOnly then Chunk.empty
                else
                  original
                    .drop(1)
                    .filterNot(_.payload.isInstanceOf[MessagePayload.Reasoning])
                    .map(_.payload)
              )
              assertEquals(turn.newMessages, original.drop(seed.messages.size).map(_.payload))
              val rewriteLog = logs.asScala
                .find(line =>
                  line.contains("Managed rebase") && line.contains("removedReasoning=Chunk(") &&
                    line.contains(MessageId.value(destination(1).id))
                )
                .get
              val removed = (destination ++ original.drop(1)).filter(_.payload.isInstanceOf[MessagePayload.Reasoning])
              removed.foreach(m => assert(rewriteLog.contains(MessageId.value(m.id))))
              retained.zip(active).foreach { case (old, copy) =>
                assert(rewriteLog.contains(s"(${old.id},${copy.id})"))
              }
              assert(!logs.asScala.exists(_.contains("SECRET-CONTINUATION")))
              assert(!logs.asScala.exists(_.contains("SECRET-SUMMARY")))
            }
          }
        }
      }
    }

    for competingPointer <- List(false, true) do
      test(
        s"$backend: native mapped fake provider sees failed tool -> successful retry -> rebase -> continued execution (competing pointer=$competingPointer)"
      ) {
        val logs = new CopyOnWriteArrayList[String]()
        withStorage(backend) { storage =>
          withLogs(logs) {
            for {
              orchestrator <- make(storage)
              requests <- Ref.make(Chunk.empty[AgentTurnRequest])
              effects <- Ref.make(0)
              before <- Ref.make(Chunk.empty[Message])
              retained <- Ref.make(Chunk.empty[Message])
              captured <- Promise.make[Nothing, ManagedHistory]
              competitor <- Ref.make(Option.empty[Session])
              provider = new Agent[Any] {
                val id = agentId
                def turn(request: AgentTurnRequest) = requests.updateAndGet(_ :+ request).map { seen =>
                  AgentTurnResponse(
                    if seen.size == 1 then Chunk(reasoningPayload, callPayload("failed"))
                    else if seen.size == 2 then Chunk(reasoningPayload, callPayload("retry"))
                    else if seen.size == 3 then Chunk(callPayload("continue"))
                    else Chunk(MessagePayload.Assistant("finished")),
                    fmgp.yallmo.model.llm.LlmStopReason.Stop
                  )
                }
              }
              native = NativeFunctionAgent[Agent[Any]](agentId, "recording-fake").bind[Any](provider)
              agent = new AgentInstance[Any] {
                val id = agentId
                def next(task: AgentTask, context: StaticContext[Any], current: RunContext) =
                  native.next(task, context, current)
                override def nextManaged(
                    task: AgentTask,
                    context: StaticContext[Any],
                    current: RunContext,
                    history: ManagedHistory
                ) =
                  for {
                    _ <- captured.succeed(history)
                    active <- history.messages
                    refreshed <-
                      if active.exists(_.payload == outputPayload("retry", "success")) &&
                        active.exists(_.payload.isInstanceOf[MessagePayload.Reasoning])
                      then
                        for {
                          _ <- before.set(active)
                          failedResult = active
                            .find(_.payload match {
                              case MessagePayload.ToolOutput(output) => output.callId == FunctionCallId.unsafe("failed")
                              case _                                 => false
                            })
                            .get
                          failedCall = active.find(_.payload == callPayload("failed")).get
                          // The failure result is exclusive base; the pre-failure parent is the destination.
                          // Reasoning preceding the call is sanitized too; it is not a new execution.
                          _ <- orchestrator
                            .append(sessionId, userId, Chunk(MessagePayload.User("competitor")))
                            .flatMap(saved => competitor.set(Some(saved)))
                            .when(competingPointer)
                          copies <- history.rebase(failedResult.id, active.last.id, failedCall.parentId)
                          _ <- retained.set(copies)
                          fresh <-
                            history.messages // Explicitly refresh/rebuild before next inference, in this submission.
                        } yield RunContext(fresh.map(_.payload))
                      else ZIO.succeed(current)
                    result <- native.next(task, context, refreshed)
                  } yield result
              }
              tool = toolWith(
                effects
                  .updateAndGet(_ + 1)
                  .flatMap(n =>
                    if n == 1 then ZIO.fail(YallmoError.InvalidConfig("first attempt failed"))
                    else ZIO.succeed(ExternalToolResult("success"))
                  )
              )
              turn <- orchestrator.submit(sessionId, userId, AgentTask("retry once"), execution(agent, tool))
              count <- effects.get
              seen <- requests.get
              original <- before.get
              copies <- retained.get
              history <- captured.await
              active <- history.messages
              reopened <- reload(storage)
              all <- reopened.listMessages
              saved <- reopened.loadSession(sessionId)
              competing <- competitor.get
            } yield {
              assertEquals(count, 3) // Failed attempt, successful retry, continued tool; rebase never executes tools.
              assertEquals(seen.size, 4)
              assert(seen(1).input.exists(_.contentStr.contains("first attempt failed")))
              val failurePair = original.filter(_.payload match {
                case MessagePayload.ToolCall(call)     => call.id == FunctionCallId.unsafe("failed")
                case MessagePayload.ToolOutput(output) => output.callId == FunctionCallId.unsafe("failed")
                case _                                 => false
              })
              assertEquals(failurePair.size, 2)
              assert(failurePair.forall(all.contains))
              assert(failurePair.forall(m => turn.newMessages.contains(m.payload)))
              assert(failurePair.forall(m => !active.exists(_.payload == m.payload)))
              assert(failurePair.forall(m => !seen(2).input.contains(m.payload)))
              assert(!seen(2).input.exists(_.isInstanceOf[MessagePayload.Reasoning]))
              assert(!seen(3).input.exists(_.isInstanceOf[MessagePayload.Reasoning]))
              assertEquals(copies.map(_.payload), Chunk(callPayload("retry"), outputPayload("retry", "success")))
              assert(copies.forall(m => seen(2).input.contains(m.payload)))
              assertEquals(
                copies.map(_.data.copy(parentId = None)),
                original.takeRight(2).map(_.data.copy(parentId = None))
              )
              assert(original.forall(all.contains))
              assertEquals(saved.messages, competing.fold(active)(_.messages))
              if competingPointer then
                assert(
                  logs.asScala.exists(line =>
                    line.contains("level=ERROR") &&
                      line.contains("pointer publication failed")
                  )
                )
              assertEquals(active.last.payload, MessagePayload.Assistant("finished"))
              assertEquals(active.drop(1).map(_.parentId), active.dropRight(1).map(m => Some(m.id)))
              assertEquals(turn.newMessages.count(_.isInstanceOf[MessagePayload.ToolOutput]), 3)
              assert(!logs.asScala.exists(_.contains("SECRET-CONTINUATION")))
            }
          }
        }
      }

    test(s"$backend: tool completing after rebase remains original-parent audit, never active context") {
      withStorage(backend) { storage =>
        for {
          orchestrator <- make(storage)
          captured <- Promise.make[Nothing, ManagedHistory]
          entered <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          observations <- Ref.make(Chunk.empty[Chunk[Message]])
          effects <- Ref.make(0)
          agent = observingAgent(observations, captured)
          tool = toolWith(effects.update(_ + 1) *> entered.succeed(()) *> release.await.as(ExternalToolResult("late")))
          stopped <- Ref.make(false)
          inspectAudit = new AgentExecution[Any] {
            val agentId = ManagedHistorySubmitSuite.this.agentId
            private[orchestration] def run(task: AgentTask, history: ManagedHistory) =
              execution(agent, tool).run(task, history).catchAll { error =>
                // A trusted test program exposes the audit after the runner stops; it never resumes the loop.
                stopped
                  .set(error.isInstanceOf[YallmoError.InvalidAgentDecision])
                  .as(
                    AgentTurnResult(AgentResult(agentId, "stopped"), RunContext())
                  )
              }
          }
          fiber <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), inspectAudit).fork
          _ <- entered.await
          history <- captured.await
          original <- history.messages
          // Drop the pending call as part of an empty selected suffix; never synthesize a result or pair across a cut.
          copies <- history.rebase(original.last.id, original.last.id, Some(original.head.id))
          replacement <- history.messages
          _ <- release.succeed(())
          result <- fiber.await
          active <- history.messages
          count <- effects.get
          seen <- observations.get
          reopened <- reload(storage)
          all <- reopened.listMessages
          saved <- reopened.loadSession(sessionId)
          didStop <- stopped.get
        } yield {
          assert(didStop)
          assert(result.isSuccess)
          assertEquals(copies, Chunk.empty)
          assertEquals(active, replacement)
          assertEquals(active, original.take(1))
          assertEquals(count, 1)
          assertEquals(seen.size, 1)
          assert(original.forall(all.contains))
          val outputs = all.filter(_.payload.isInstanceOf[MessagePayload.ToolOutput])
          assertEquals(outputs.size, 1)
          assertEquals(outputs.head.parentId, Some(original.last.id))
          val turn = result.toEither.toOption.get
          assertEquals(turn.newMessages, original.map(_.payload) :+ outputs.head.payload)
          assert(!active.exists(_.payload == outputs.head.payload))
          assertEquals(saved.messages, active)
        }
      }
    }

    for protocol <- List[MessagePayload](
        MessagePayload.ToolCall(FunctionCall(FunctionCallId.unsafe("call"), toolKey, "{}")),
        MessagePayload.ToolOutput(FunctionGenericOutput(FunctionCallId.unsafe("call"), toolKey, "actual result"))
      )
    do {
      test(s"$backend: changed ${protocol.getClass.getSimpleName} context rejected; exact no-op allowed") {
        withStorage(backend) { storage =>
          for {
            orchestrator <- make(storage)
            seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
            execution = new AgentExecution[Any] {
              val agentId = ManagedHistorySubmitSuite.this.agentId
              private[orchestration] def run(task: AgentTask, history: ManagedHistory) = for {
                _ <- history.append(Chunk(protocol))
                current <- history.messages
                rejected <- history.rebase(seed.messages.head.id, current.last.id, None).exit
                unchanged <- history.messages
                copies <- history.rebase(seed.messages.head.id, current.last.id, Some(seed.messages.head.id))
                _ <- ZIO.succeed {
                  assert(rejected.isFailure)
                  assertEquals(unchanged, current)
                  assertEquals(copies, current.drop(1))
                }
              } yield AgentTurnResult(AgentResult(agentId, "done"), RunContext())
            }
            _ <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), execution)
          } yield ()
        }
      }
    }

    test(s"$backend: append waiting behind active rewrite attaches only to replacement") {
      withStorage(backend) { storage =>
        for {
          entered <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          probe = new StorageProbe(storage) {
            override def putMessage(message: Message) =
              if message.parentId.isEmpty && message.payload == MessagePayload.User("prompt") then
                entered.succeed(()) *> release.await *> super.putMessage(message)
              else super.putMessage(message)
          }
          orchestrator <- make(probe)
          seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
          execution = new AgentExecution[Any] {
            val agentId = ManagedHistorySubmitSuite.this.agentId
            private[orchestration] def run(task: AgentTask, history: ManagedHistory) = for {
              current <- history.messages
              rewrite <- history.rebase(seed.messages.head.id, current.last.id, None).fork
              _ <- entered.await
              appended <- history.append(Chunk(MessagePayload.Assistant("concurrent"))).fork
              _ <- release.succeed(())
              copies <- rewrite.join
              _ <- appended.join
              active <- history.messages
              _ <- ZIO.succeed {
                assertEquals(active.dropRight(1), copies)
                assertEquals(active.last.parentId, Some(copies.last.id))
              }
            } yield AgentTurnResult(AgentResult(agentId, "done"), RunContext())
          }
          _ <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), execution)
        } yield ()
      }
    }

    test(s"$backend: stale extension output is retained under old parent and stops before inference") {
      withStorage(backend) { storage =>
        for {
          orchestrator <- make(storage)
          seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
          captured <- Promise.make[Nothing, ManagedHistory]
          old <- Ref.make(Option.empty[MessageId])
          inferred <- Ref.make(false)
          extension = new ContextExtension[Any] {
            val key = "stale"
            def apply(context: StaticContext[Any], current: RunContext) = for {
              history <- captured.await
              original <- history.messages
              _ <- old.set(Some(original.last.id))
              _ <- history.rebase(seed.messages.head.id, original.last.id, None)
            } yield current.append(MessagePayload.Context(key, "old execution"))
          }
          agent = new AgentInstance[Any] {
            val id = agentId
            def next(task: AgentTask, context: StaticContext[Any], current: RunContext) =
              inferred.set(true).as(finalTurn)
          }
          runner = new AgentRunner[Any](Map.empty, Map.empty)
          execution = new AgentExecution[Any] {
            val agentId = ManagedHistorySubmitSuite.this.agentId
            private[orchestration] def run(task: AgentTask, history: ManagedHistory) =
              captured.succeed(history) *> runner
                .execution(agent, context.copy(extensions = Chunk(extension)))
                .run(task, history)
          }
          failed <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), execution).exit
          inventory <- storage.listMessages
          parent <- old.get
          ran <- inferred.get
          active <- captured.await.flatMap(_.messages)
        } yield {
          assert(failed.isFailure && !ran)
          assertEquals(active.map(_.payload), Chunk(MessagePayload.User("prompt")))
          assertEquals(
            inventory.find(_.payload == MessagePayload.Context("stale", "old execution")).map(_.parentId),
            Some(parent)
          )
        }
      }
    }

    test(s"$backend: tool side effect executes once; rewrite cannot break in-flight call/output correlation") {
      withStorage(backend) { storage =>
        for {
          orchestrator <- make(storage)
          seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
          captured <- Promise.make[Nothing, ManagedHistory]
          observed <- Ref.make(Chunk.empty[Chunk[Message]])
          sideEffects <- Ref.make(0)
          tool = toolWith {
            for {
              _ <- sideEffects.update(_ + 1)
              history <- captured.await
              before <- history.messages
              rejected <- history.rebase(seed.messages.head.id, before.last.id, None).exit
              after <- history.messages
              _ <- ZIO.succeed { assert(rejected.isFailure); assertEquals(after, before) }
            } yield ExternalToolResult("actual side effect")
          }
          _ <- orchestrator.submit(
            sessionId,
            userId,
            AgentTask("run"),
            execution(observingAgent(observed, captured), tool)
          )
          count <- sideEffects.get
          active <- captured.await.flatMap(_.messages)
        } yield {
          assertEquals(count, 1)
          val calls = active.collect { case Message(_, MessageData(_, _, _, MessagePayload.ToolCall(call))) => call.id }
          val outputs = active.collect { case Message(_, MessageData(_, _, _, MessagePayload.ToolOutput(output))) =>
            output.callId
          }
          assertEquals(calls, outputs)
          assertEquals(calls.size, 1)
        }
      }
    }

    test(s"$backend: turn after managed rebase renders the fresh chain, never the accepted prefix") {
      withStorage(backend) { storage =>
        for {
          orchestrator <- make(storage)
          seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
          turns <- Ref.make(0)
          agent = new AgentInstance[Any] {
            val id = agentId
            def next(task: AgentTask, context: StaticContext[Any], current: RunContext) = ZIO.dieMessage("managed only")
            override def nextManaged(
                task: AgentTask,
                context: StaticContext[Any],
                current: RunContext,
                history: ManagedHistory
            ) =
              turns.updateAndGet(_ + 1).flatMap {
                case 1 =>
                  for {
                    original <- history.messages
                    _ <- history.rebase(seed.messages.head.id, original.last.id, None)
                    fresh <- history.messages
                    _ <- ZIO.succeed(assertEquals(fresh.map(_.payload), Chunk(MessagePayload.User("prompt"))))
                  } yield AgentTurn(
                    None,
                    AgentDecision.Actions(Chunk(LlmAction.RetrieveRag("refresh", RagQuery("query"))))
                  )
                case _ =>
                  ZIO.succeed {
                    assertEquals(current.messages.head, MessagePayload.User("prompt"))
                    assertEquals(current.messages.size, 2)
                    assert(current.messages.last.isInstanceOf[MessagePayload.RagResult])
                    finalTurn
                  }
              }
          }
          retriever = new KnowledgeRetriever[Any] {
            val definition = RetrieverDefinition("refresh", "fresh turn")
            def retrieve(query: RagQuery) = ZIO.succeed(Chunk.empty)
          }
          turn <- orchestrator.submit(
            sessionId,
            userId,
            AgentTask("prompt"),
            new AgentRunner[Any](Map.empty, Map("refresh" -> retriever))
              .execution(agent, context.copy(retrievers = Chunk(retriever.definition)))
          )
          count <- turns.get
        } yield {
          assertEquals(count, 2)
          assertEquals(turn.newMessages, turn.session.messages.map(_.payload))
          assertEquals(turn.session.messages.head.parentId, None)
        }
      }
    }

    for boundary <- List("before-first", "between-actions", "running") do {
      for (competingPointer, mutation) <- List(false, true).flatMap(pointer =>
          List("rewrite", "append", "restored", "unchanged").map((pointer, _))
        )
      do {
        test(s"$backend: RAG head guard at $boundary mutation=$mutation competing pointer=$competingPointer") {
          val logs = new CopyOnWriteArrayList[String]()
          withStorage(backend) { storage =>
            withLogs(logs) {
              for {
                orchestrator <- make(storage)
                seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
                captured <- Promise.make[Nothing, ManagedHistory]
                reached <- Promise.make[Nothing, Unit]
                release <- Promise.make[Nothing, Unit]
                effects <- Ref.make(Chunk.empty[String])
                turns <- Ref.make(0)
                agent = new AgentInstance[Any] {
                  val id = agentId
                  def next(task: AgentTask, context: StaticContext[Any], current: RunContext) =
                    turns.updateAndGet(_ + 1).map { count =>
                      if count == 1 then
                        AgentTurn(
                          Some(MessagePayload.Assistant("decision")),
                          AgentDecision
                            .Actions(Chunk("first", "second").map(key => LlmAction.RetrieveRag(key, RagQuery(key))))
                        )
                      else finalTurn
                    }
                }
                retrievers = Chunk("first", "second").map { key =>
                  key -> new KnowledgeRetriever[Any] {
                    val definition = RetrieverDefinition(key, "RAG fence probe")
                    def retrieve(query: RagQuery) =
                      effects.update(_ :+ key) *>
                        (reached.succeed(()) *> release.await).when(boundary == "running" && key == "first") *>
                        ZIO.succeed(Chunk.empty)
                  }
                }.toMap
                runnerExecution = new AgentRunner[Any](Map.empty, retrievers)
                  .execution(agent, context.copy(retrievers = Chunk.fromIterable(retrievers.values.map(_.definition))))
                execution = new AgentExecution[Any] {
                  val agentId = ManagedHistorySubmitSuite.this.agentId
                  private[orchestration] def run(task: AgentTask, history: ManagedHistory) = {
                    val gated = new ManagedHistory {
                      def initialMessages = history.initialMessages
                      def messages = history.messages
                      def rebase(base: MessageId, head: MessageId, onto: Option[MessageId]) =
                        history.rebase(base, head, onto)
                      private[orchestration] def snapshot = history.snapshot
                      private[orchestration] def append(payloads: Chunk[MessagePayload]) = history.append(payloads)
                      private[orchestration] def appendAt(
                          payloads: Chunk[MessagePayload],
                          expectedParent: Option[MessageId]
                      ) = {
                        val stop =
                          boundary == "before-first" && payloads.contains(MessagePayload.Assistant("decision")) ||
                            boundary == "between-actions" && payloads.exists {
                              case MessagePayload.RagResult("first", _) => true
                              case _                                    => false
                            }
                        history
                          .appendAt(payloads, expectedParent)
                          .tap(_ => (reached.succeed(()) *> release.await).when(stop))
                      }
                    }
                    captured.succeed(history) *> runnerExecution.run(task, gated)
                  }
                }
                fiber <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), execution).fork
                _ <- reached.await
                history <- captured.await
                original <- history.messages
                competitor <-
                  if competingPointer then
                    orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("competitor"))).map(Some(_))
                  else ZIO.succeed(None)
                copies <- mutation match {
                  case "rewrite"  => history.rebase(seed.messages.head.id, original.last.id, None)
                  case "append"   => history.append(Chunk(MessagePayload.Context("concurrent", "keep"))).as(Chunk.empty)
                  case "restored" =>
                    history.rebase(original.last.id, original.last.id, Some(original.head.id)) *>
                      history.rebase(original.head.id, original.head.id, Some(original.last.id))
                  case _ => ZIO.succeed(Chunk.empty)
                }
                replacement <- history.messages
                _ <- release.succeed(())
                failed <- fiber.await
                active <- history.messages
                ran <- effects.get
                turnCount <- turns.get
                reopened <- reload(storage)
                saved <- reopened.loadSession(sessionId)
                inventory <- reopened.listMessages
              } yield {
                val accepted = mutation == "restored" || mutation == "unchanged"
                assertEquals(failed.isSuccess, accepted)
                assertEquals(turnCount, if accepted then 2 else 1)
                assertEquals(
                  ran,
                  if accepted then Chunk("first", "second")
                  else if boundary == "before-first" then Chunk.empty
                  else Chunk("first")
                )
                assert(original.forall(inventory.contains))
                assert(replacement.forall(inventory.contains))
                if accepted then {
                  assertEquals(replacement, original)
                  assertEquals(active.take(original.size), original)
                  assertEquals(active.last.payload, finalTurn.assistant.get)
                  assertEquals(active.count(_.payload.isInstanceOf[MessagePayload.RagResult]), 2)
                  assertEquals(active.drop(1).map(_.parentId), active.dropRight(1).map(m => Some(m.id)))
                  val turn = failed.toEither.toOption.get
                  assertEquals(turn.newMessages, active.drop(seed.messages.size).map(_.payload))
                } else {
                  assertEquals(active, replacement)
                  if mutation == "rewrite" then {
                    assertEquals(replacement, copies)
                    assertEquals(replacement.head.parentId, None)
                  }
                  assert(!inventory.exists(_.payload == MessagePayload.RagResult("second", Chunk.empty)))
                  val originalResult = inventory.find(m =>
                    m.payload == MessagePayload.RagResult("first", Chunk.empty) &&
                      m.parentId == original.find(_.payload == MessagePayload.Assistant("decision")).map(_.id)
                  )
                  if boundary == "before-first" then assertEquals(originalResult, None)
                  else assert(originalResult.nonEmpty)
                  if boundary == "running" then {
                    assert(!active.exists(_.payload.isInstanceOf[MessagePayload.RagResult]))
                    assertEquals(inventory.count(_.payload == MessagePayload.RagResult("first", Chunk.empty)), 1)
                  }
                }
                assertEquals(saved.messages, competitor.fold(active)(_.messages))
                if competingPointer then
                  assert(
                    logs.asScala.exists(line =>
                      line.contains("level=ERROR") && line.contains("pointer publication failed") &&
                        line.contains(s"session=${sessionId.value}") && line.contains("old=") && line.contains("new=")
                    )
                  )
              }
            }
          }
        }
      }
    }

    for
      phase <- List("extension", "after-extension", "model");
      mutation <- List("rewrite", "append", "restored", "pointer")
    do
      test(s"$backend: in-flight $phase output uses captured head after $mutation") {
        withStorage(backend) { storage =>
          for {
            orchestrator <- make(storage)
            seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
            captured <- Promise.make[Nothing, ManagedHistory]
            reached <- Promise.make[Nothing, Unit]
            release <- Promise.make[Nothing, Unit]
            effects <- Ref.make(0)
            inferred <- Ref.make(0)
            extensionPayload = MessagePayload.Context("extension", "completed")
            extension = new ContextExtension[Any] {
              val key = "captured-head"
              def apply(context: StaticContext[Any], current: RunContext) =
                effects.update(_ + 1) *> (reached.succeed(()) *> release.await).when(phase == "extension") *>
                  ZIO.succeed(current.append(extensionPayload))
            }
            agent = new AgentInstance[Any] {
              val id = agentId
              def next(task: AgentTask, context: StaticContext[Any], current: RunContext) =
                inferred.update(_ + 1) *> (reached.succeed(()) *> release.await).when(phase == "model") *>
                  ZIO.succeed(finalTurn)
            }
            execution = new AgentExecution[Any] {
              val agentId = ManagedHistorySubmitSuite.this.agentId
              private[orchestration] def run(task: AgentTask, history: ManagedHistory) = {
                val gated = new ManagedHistory {
                  def initialMessages = history.initialMessages
                  def messages = history.messages
                  def rebase(base: MessageId, head: MessageId, onto: Option[MessageId]) =
                    history.rebase(base, head, onto)
                  private[orchestration] def snapshot = history.snapshot
                  private[orchestration] def append(payloads: Chunk[MessagePayload]) = history.append(payloads)
                  private[orchestration] def appendAt(
                      payloads: Chunk[MessagePayload],
                      expectedParent: Option[MessageId]
                  ) =
                    history
                      .appendAt(payloads, expectedParent)
                      .tap(_ =>
                        (reached.succeed(()) *> release.await)
                          .when(phase == "after-extension" && payloads.contains(extensionPayload))
                      )
                }
                captured.succeed(history) *> new AgentRunner[Any](Map.empty, Map.empty)
                  .execution(agent, context.copy(extensions = Chunk(extension)))
                  .run(task, gated)
              }
            }
            running <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), execution).fork
            _ <- reached.await
            history <- captured.await
            original <- history.messages
            competitor <- mutation match {
              case "rewrite"  => history.rebase(seed.messages.head.id, original.last.id, None).as(None)
              case "append"   => history.append(Chunk(MessagePayload.Context("concurrent", "keep"))).as(None)
              case "restored" =>
                history.rebase(original.last.id, original.last.id, Some(original.head.id)) *>
                  history.rebase(original.head.id, original.head.id, Some(original.last.id)).as(None)
              case _ => orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("competitor"))).map(Some(_))
            }
            replacement <- history.messages
            _ <- release.succeed(())
            result <- running.await
            active <- history.messages
            inventory <- storage.listMessages
            saved <- storage.loadSession(sessionId)
            extensionCount <- effects.get
            modelCount <- inferred.get
          } yield {
            val accepted = mutation == "restored" || mutation == "pointer"
            val produced = if phase == "model" then finalTurn.assistant.get else extensionPayload
            val parent = if phase == "after-extension" then original.dropRight(1).lastOption.map(_.id)
            else original.lastOption.map(_.id)
            assertEquals(result.isSuccess, accepted)
            assertEquals(extensionCount, 1)
            assertEquals(modelCount, if phase != "model" && !accepted then 0 else 1)
            assertEquals(inventory.count(m => m.payload == produced && m.parentId == parent), 1)
            assert(original.forall(inventory.contains))
            if accepted then {
              assertEquals(replacement, original)
              assertEquals(active.last.payload, finalTurn.assistant.get)
              val turn = result.toEither.toOption.get
              assertEquals(
                turn.newMessages,
                Chunk(MessagePayload.User("prompt"), extensionPayload, finalTurn.assistant.get)
              )
            } else assertEquals(active, replacement)
            assertEquals(saved.messages, competitor.fold(active)(_.messages))
          }
        }
      }

    test(s"$backend: atomic appends return their own head for None, empty batches and no-op; closed handle rejects") {
      withStorage(backend) { storage =>
        for {
          orchestrator <- make(storage)
          captured <- Promise.make[Nothing, ManagedHistory]
          execution = new AgentExecution[Any] {
            val agentId = ManagedHistorySubmitSuite.this.agentId
            private[orchestration] def run(task: AgentTask, history: ManagedHistory) = for {
              _ <- captured.succeed(history)
              accepted <- history.messages
              _ <- history.rebase(accepted.last.id, accepted.last.id, None)
              empty <- history.snapshot
              root <- history.appendAt(Chunk.empty, None)
              first <- history.appendAt(Chunk(MessagePayload.Assistant("first")), root)
              same <- history.appendAt(Chunk.empty, first)
              current <- history.messages
              _ <- history.rebase(current.last.id, current.last.id, first)
              last <- history.appendAt(Chunk(MessagePayload.Assistant("last")), same)
              finalMessages <- history.messages
              _ <- ZIO.succeed {
                assertEquals(empty, Chunk.empty)
                assertEquals(root, None)
                assertEquals(first, same)
                assertEquals(last, finalMessages.lastOption.map(_.id))
                assertEquals(finalMessages.map(_.parentId), Chunk(None, first))
              }
            } yield AgentTurnResult(AgentResult(agentId, "last"), RunContext(finalMessages.map(_.payload)))
          }
          turn <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), execution)
          history <- captured.await
          late <- history.appendAt(Chunk.empty, turn.session.messages.lastOption.map(_.id)).exit
        } yield {
          assert(late.isFailure)
          assertEquals(
            turn.newMessages,
            Chunk(MessagePayload.User("prompt"), MessagePayload.Assistant("first"), MessagePayload.Assistant("last"))
          )
        }
      }
    }

    test(s"$backend: two changed managed activations publish both branches and keep execution audit") {
      withStorage(backend) { storage =>
        for {
          firstPublished <- Promise.make[Nothing, Session]
          secondPublished <- Promise.make[Nothing, Session]
          probe = new StorageProbe(storage) {
            override def compareAndSetSessionHead(id: SessionId, old: Option[MessageId], next: Option[MessageId]) =
              super.compareAndSetSessionHead(id, old, next).tap { saved =>
                if saved.messages.headOption.exists(_.payload == MessagePayload.User("prompt")) &&
                  saved.messages.lastOption.exists(_.payload == MessagePayload.Assistant("actual"))
                then firstPublished.succeed(saved).unit
                else if saved.messages.headOption.exists(_.payload == MessagePayload.User("destination")) &&
                  saved.messages.lastOption.exists(_.payload == MessagePayload.Assistant("between"))
                then secondPublished.succeed(saved).unit
                else ZIO.unit
              }
          }
          orchestrator <- make(probe)
          seed <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("seed")))
          destination <- Clock.instant.flatMap(time =>
            storage.putMessage(Message(MessageData(userId, None, time, MessagePayload.User("destination"))))
          )
          retained <- Ref.make(Chunk.empty[Message])
          captured <- Promise.make[Nothing, ManagedHistory]
          execution = new AgentExecution[Any] {
            val agentId = ManagedHistorySubmitSuite.this.agentId
            private[orchestration] def run(task: AgentTask, history: ManagedHistory) = for {
              _ <- captured.succeed(history)
              _ <- history.append(Chunk(MessagePayload.Assistant("actual")))
              original <- history.messages
              first <- history.rebase(seed.messages.head.id, original.last.id, None)
              firstActive <- history.messages
              firstSaved <- firstPublished.await
              _ <- history.append(Chunk(MessagePayload.Assistant("between")))
              intermediate <- history.messages
              second <- history.rebase(first.head.id, intermediate.last.id, Some(destination.id))
              secondActive <- history.messages
              secondSaved <- secondPublished.await
              _ <- ZIO.succeed {
                assertEquals(firstActive, first)
                assert(firstActive != original)
                assertEquals(firstSaved.messages, firstActive)
                assertEquals(secondActive, Chunk(destination) ++ second)
                assert(secondActive != intermediate)
                assertEquals(secondSaved.messages, secondActive)
                assertEquals(
                  second.map(_.payload),
                  Chunk(MessagePayload.Assistant("actual"), MessagePayload.Assistant("between"))
                )
              }
              _ <- retained.set(original ++ firstActive ++ intermediate ++ secondActive)
              _ <- history.append(Chunk(MessagePayload.Assistant("after")))
            } yield AgentTurnResult(AgentResult(agentId, "after"), RunContext())
          }
          turn <- orchestrator.submit(sessionId, userId, AgentTask("prompt"), execution)
          active <- captured.await.flatMap(_.messages)
          originalsAndCopies <- retained.get
          reopened <- reload(storage)
          saved <- reopened.loadSession(sessionId)
          inventory <- reopened.listMessages
        } yield {
          assertEquals(active, saved.messages)
          assertEquals(turn.session, saved)
          assertEquals(
            active.map(_.payload),
            Chunk(
              MessagePayload.User("destination"),
              MessagePayload.Assistant("actual"),
              MessagePayload.Assistant("between"),
              MessagePayload.Assistant("after")
            )
          )
          assertEquals(
            turn.newMessages,
            Chunk(
              MessagePayload.User("prompt"),
              MessagePayload.Assistant("actual"),
              MessagePayload.Assistant("between"),
              MessagePayload.Assistant("after")
            )
          )
          assert(originalsAndCopies.forall(inventory.contains))
          assert(active.forall(inventory.contains))
          assertEquals(active.last.parentId, Some(active.dropRight(1).last.id))
        }
      }
    }

    for mutation <- List("truncate", "replace") do {
      test(s"$backend: $mutation extension prefix is rejected without corrupting active history after failed writes") {
        withStorage(backend) { storage =>
          for {
            attempted <- Promise.make[Nothing, Unit]
            captured <- Promise.make[Nothing, ManagedHistory]
            seen <- Ref.make(Chunk.empty[Chunk[Message]])
            extensionCalls <- Ref.make(0)
            beforeMutation <- Ref.make(RunContext())
            probe = new StorageProbe(storage) {
              override def putMessage(message: Message): IO[YallmoError, Message] =
                attempted.succeed(()) *> ZIO.fail(failure)
            }
            orchestrator <- make(probe)
            extension = new ContextExtension[Any] {
              val key = "invalid-prefix"
              def apply(context: StaticContext[Any], current: RunContext): IO[YallmoError, RunContext] =
                extensionCalls.updateAndGet(_ + 1).flatMap {
                  case 1 => ZIO.succeed(current.append(MessagePayload.Context(key, "accepted")))
                  case _ =>
                    beforeMutation
                      .set(current)
                      .as(
                        if mutation == "truncate" then RunContext(current.messages.take(1))
                        else RunContext(current.messages.updated(0, MessagePayload.User("replacement")))
                      )
                }
            }
            tool = toolWith(attempted.await.as(ExternalToolResult("result")))
            result <- orchestrator
              .submit(
                sessionId,
                userId,
                AgentTask("preserve"),
                new AgentRunner[Any](Map(toolKey -> tool), Map.empty).execution(
                  observingAgent(seen, captured),
                  context.copy(tools = Chunk(tool.definition), extensions = Chunk(extension))
                )
              )
              .either
            history <- captured.await
            active <- history.messages
            before <- beforeMutation.get
            stored <- storage.loadSession(sessionId)
          } yield {
            assert(result.swap.toOption.get.error.isInstanceOf[YallmoError.InvalidAgentDecision])
            assertEquals(active.map(_.payload), before.messages)
            assertEquals(active.head.payload, MessagePayload.User("preserve"))
            assertEquals(active.count(_.payload.isInstanceOf[MessagePayload.ToolOutput]), 1)
            assertEquals(stored.messages.map(_.payload), Chunk(MessagePayload.User("preserve")))
          }
        }
      }
    }

    for mode <- List("pre-object", "post-object", "post-session", "post-session-reload") do {
      test(s"$backend: $mode failure retries exact identified objects without duplication") {
        withStorage(backend) { storage =>
          for {
            first <- Ref.make(true)
            failRead <- Ref.make(false)
            failed <- Promise.make[Nothing, Unit]
            attempts <- Ref.make(Chunk.empty[Message])
            observed <- Ref.make(Chunk.empty[Chunk[Message]])
            captured <- Promise.make[Nothing, ManagedHistory]
            probe = new StorageProbe(storage) {
              override def putMessage(message: Message): IO[YallmoError, Message] =
                attempts.update(_ :+ message) *> (if mode.startsWith("post-session") then super.putMessage(message)
                                                  else
                                                    first.getAndSet(false).flatMap {
                                                      case false => super.putMessage(message)
                                                      case true  =>
                                                        super.putMessage(message).when(mode == "post-object") *>
                                                          failed.succeed(()) *> ZIO.fail(failure)
                                                    })

              override def compareAndSetSessionHead(
                  id: SessionId,
                  head: Option[MessageId],
                  newHead: Option[MessageId]
              ): IO[YallmoError, Session] =
                if !mode.startsWith("post-session") then super.compareAndSetSessionHead(id, head, newHead)
                else
                  super.compareAndSetSessionHead(id, head, newHead).flatMap { session =>
                    first.getAndSet(false).flatMap {
                      case false => ZIO.succeed(session)
                      case true  =>
                        failRead.set(mode == "post-session-reload") *> failed.succeed(()) *> ZIO.fail(failure)
                    }
                  }
              override def loadSession(id: SessionId): IO[YallmoError, Session] =
                failRead.getAndSet(false).flatMap {
                  case true  => ZIO.fail(failure)
                  case false => super.loadSession(id)
                }
            }
            orchestrator <- make(probe)
            agent = observingAgent(observed, captured)
            tool = toolWith(failed.await.as(ExternalToolResult("after failure")))
            turn <- orchestrator.submit(sessionId, userId, AgentTask("retry"), execution(agent, tool))
            history <- captured.await
            active <- history.messages
            tried <- attempts.get
            stored <- storage.loadSession(sessionId)
            all <- storage.listMessages
          } yield {
            assertEquals(stored.messages, active)
            assertEquals(turn.session, stored)
            assertEquals(all.size, active.size)
            assertEquals(all.map(_.id).toSet, active.map(_.id).toSet)
            assertEquals(tried.map(_.id).distinct, active.drop(1).map(_.id))
            assert(tried.forall(message => active.contains(message))) // Includes timestamps, parents and hashes.
            if !mode.startsWith("post-session") then {
              assertEquals(tried.take(2), Chunk(active(1), active(1)))
              assertEquals(tried.size, active.size) // Exactly one failed-object retry.
            }
          }
        }
      }
    }

    test(
      s"$backend: concurrent ordinary append keeps its pointer but every original generated object remains inspectable"
    ) {
      withStorage(backend) { storage =>
        val logs = new CopyOnWriteArrayList[String]()
        withLogs(logs) {
          for {
            entered <- Promise.make[Nothing, Unit]
            release <- Promise.make[Nothing, Unit]
            first <- Ref.make(true)
            observed <- Ref.make(Chunk.empty[Chunk[Message]])
            captured <- Promise.make[Nothing, ManagedHistory]
            probe = new StorageProbe(storage) {
              override def putMessage(message: Message): IO[YallmoError, Message] =
                first.getAndSet(false).flatMap { block =>
                  (entered.succeed(()) *> release.await).when(block) *> super.putMessage(message)
                }
            }
            orchestrator <- make(probe)
            agent = observingAgent(observed, captured)
            competitor <- Promise.make[Nothing, Session]
            tool = toolWith {
              for {
                _ <- entered.await
                saved <- orchestrator.append(sessionId, userId, Chunk(MessagePayload.User("concurrent")))
                _ <- competitor.succeed(saved)
                _ <- release.succeed(())
              } yield ExternalToolResult("continue on original branch")
            }
            turn <- orchestrator.submit(sessionId, userId, AgentTask("branch"), execution(agent, tool))
            history <- captured.await
            active <- history.messages
            observations <- observed.get
            competing <- competitor.await
            reopened <- reload(storage)
            current <- reopened.loadSession(sessionId)
            all <- reopened.listMessages
          } yield {
            assertEquals(current.messages, competing.messages)
            assertEquals(current.messages.last.payload, MessagePayload.User("concurrent"))
            assertEquals(turn.newMessages, active.map(_.payload))
            assertEquals(observations.last, active.dropRight(1))
            assert(active.forall(all.contains))
            assertEquals(all.size, active.size + 1)
            assertEquals(active.drop(1).map(_.parentId), active.dropRight(1).map(m => Some(m.id)))
            assert(logs.asScala.exists(_.contains("pointer publication failed")))
            assert(!logs.asScala.exists(_.contains("not stored")))
          }
        }
      }
    }

    test(s"$backend: overlapping complete submissions keep independent active histories, audits and stored branches") {
      withStorage(backend) { storage =>
        val logs = new CopyOnWriteArrayList[String]()
        withLogs(logs) {
          for {
            orchestrator <- make(storage)
            seed <- orchestrator.append(
              sessionId,
              userId,
              Chunk(MessagePayload.User("retained"), MessagePayload.Context("temporary", "disposable"))
            )
            firstEntered <- Promise.make[Nothing, ManagedHistory]
            secondEntered <- Promise.make[Nothing, ManagedHistory]
            releaseSecond <- Promise.make[Nothing, Unit]
            firstAgent = new AgentInstance[Any] {
              val id = agentId
              def next(task: AgentTask, context: StaticContext[Any], current: RunContext) =
                ZIO.dieMessage("Managed entry point required")
              override def nextManaged(
                  task: AgentTask,
                  context: StaticContext[Any],
                  current: RunContext,
                  history: ManagedHistory
              ) = for {
                before <- history.messages
                _ <- firstEntered.succeed(history)
                _ <- secondEntered.await // Both prompts are accepted and both executions are still alive.
                _ <- history.rebase(seed.messages.last.id, before.last.id, Some(seed.messages.head.id))
                _ <- history.messages // Refresh this program's accepted-work fence after its own rewrite.
              } yield AgentTurn(
                Some(MessagePayload.Assistant("first complete")),
                AgentDecision.FinalAnswer("first complete")
              )
            }
            secondAgent = new AgentInstance[Any] {
              val id = agentId
              def next(task: AgentTask, context: StaticContext[Any], current: RunContext) =
                ZIO.dieMessage("Managed entry point required")
              override def nextManaged(
                  task: AgentTask,
                  context: StaticContext[Any],
                  current: RunContext,
                  history: ManagedHistory
              ) =
                secondEntered.succeed(history) *> releaseSecond.await.as(
                  AgentTurn(
                    Some(MessagePayload.Assistant("second complete")),
                    AgentDecision.FinalAnswer("second complete")
                  )
                )
            }
            runner = new AgentRunner[Any](Map.empty, Map.empty)
            first <- orchestrator
              .submit(sessionId, userId, AgentTask("first"), runner.execution(firstAgent, context))
              .fork
            firstHistory <- firstEntered.await
            second <- orchestrator
              .submit(sessionId, userId, AgentTask("second"), runner.execution(secondAgent, context))
              .fork
            secondHistory <- secondEntered.await
            firstTurn <- first.join // Its writer drains while the second submission remains suspended.
            firstActive <- firstHistory.messages
            secondBefore <- secondHistory.messages
            pointerBefore <- storage.loadSession(sessionId)
            _ <- releaseSecond.succeed(())
            secondTurn <- second.join
            secondActive <- secondHistory.messages
            firstAfter <- firstHistory.messages
            expiredFirst <- firstHistory.rebase(firstActive.head.id, firstActive.last.id, None).either
            expiredSecond <- secondHistory.rebase(secondActive.head.id, secondActive.last.id, None).either
            reopened <- reload(storage)
            saved <- reopened.loadSession(sessionId)
            inventory <- reopened.listMessages
          } yield {
            assertEquals(
              firstActive.map(_.payload),
              Chunk(
                MessagePayload.User("retained"),
                MessagePayload.User("first"),
                MessagePayload.Assistant("first complete")
              )
            )
            assertEquals(
              secondBefore.map(_.payload),
              seed.messages.map(_.payload) ++
                Chunk(MessagePayload.User("first"), MessagePayload.User("second"))
            )
            assertEquals(pointerBefore.messages, secondBefore)
            assertEquals(firstAfter, firstActive)
            assertEquals(saved.messages, secondActive)
            assertEquals(secondActive.dropRight(1), secondBefore)
            assertEquals(secondActive.last.payload, MessagePayload.Assistant("second complete"))
            assertEquals(
              firstTurn.newMessages,
              Chunk(MessagePayload.User("first"), MessagePayload.Assistant("first complete"))
            )
            assertEquals(
              secondTurn.newMessages,
              Chunk(MessagePayload.User("second"), MessagePayload.Assistant("second complete"))
            )
            assertEquals(
              firstTurn.session.messages,
              secondBefore.dropRight(1)
            ) // Last acknowledged, not competing head.
            assertEquals(secondTurn.session.messages, secondActive)
            assert((firstActive ++ secondActive ++ seed.messages).forall(inventory.contains))
            assertEquals(inventory.map(_.id).toSet, (firstActive ++ secondActive ++ seed.messages).map(_.id).toSet)
            for branch <- List(firstActive, secondActive) do
              assertEquals(branch.drop(1).map(_.parentId), branch.dropRight(1).map(m => Some(m.id)))
            assert(expiredFirst.isLeft && expiredSecond.isLeft)
            assert(logs.asScala.exists(_.contains("level=ERROR")))
            assert(logs.asScala.exists(_.contains("pointer publication failed")))
            assert(!logs.asScala.exists(_.contains("not stored")))
          }
        }
      }
    }

    for ending <- List("inference", "tool-error", "timeout", "cancellation") do {
      test(s"$backend: $ending drains identified active messages without dropping accepted input") {
        withStorage(backend) { storage =>
          for {
            observed <- Ref.make(Chunk.empty[Chunk[Message]])
            captured <- Promise.make[Nothing, ManagedHistory]
            started <- Promise.make[Nothing, Unit]
            base = observingAgent(observed, captured)
            agent = new AgentInstance[Any] {
              val id: AgentId = agentId
              def next(task: AgentTask, context: StaticContext[Any], current: RunContext) =
                base.next(task, context, current)
              override def nextManaged(
                  task: AgentTask,
                  context: StaticContext[Any],
                  current: RunContext,
                  history: ManagedHistory
              ): IO[YallmoError, AgentTurn] =
                if ending == "inference" && current.messages.exists(_.isInstanceOf[MessagePayload.ToolOutput]) then
                  ZIO.fail(YallmoError.InvalidConfig("inference failed"))
                else base.nextManaged(task, context, current, history)
            }
            tool = toolWith {
              started.succeed(()) *> (ending match {
                case "timeout" | "cancellation" => ZIO.never
                case "tool-error"               => ZIO.fail(YallmoError.InvalidConfig("tool failed"))
                case _                          => ZIO.succeed(ExternalToolResult("before inference failure"))
              })
            }
            orchestrator <- make(storage)
            budget = AgentBudget.default.copy(timeout = if ending == "timeout" then 100.millis else 5.seconds)
            execution = new AgentRunner[Any](Map(toolKey -> tool), Map.empty)
              .execution(agent, context.copy(tools = Chunk(tool.definition)), budget)
            fiber <- orchestrator.submit(sessionId, userId, AgentTask("finish"), execution).fork
            _ <- started.await
            exit <- if ending == "cancellation" then fiber.interrupt else fiber.await
            history <- captured.await
            active <- history.messages
            reopened <- reload(storage)
            stored <- reopened.loadSession(sessionId)
          } yield {
            assertEquals(stored.messages, active)
            assertEquals(active.count(_.payload == MessagePayload.User("finish")), 1)
            if ending == "tool-error" then {
              assert(exit.isSuccess)
              assert(active.exists(_.payload.contentStr.contains("tool failed")))
            } else {
              assert(exit.isFailure)
              if ending == "timeout" then
                assert(exit.causeOption.get.failureOption.get.error.isInstanceOf[YallmoError.AgentTimedOut])
              if ending == "cancellation" then assert(exit.causeOption.get.isInterrupted)
            }
            assertEquals(active.count(_.payload.isInstanceOf[MessagePayload.ToolCall]), 1)
          }
        }
      }
    }

    for snapshot <- List("empty", "replaced", "duplicated") do {
      test(s"$backend: returned $snapshot compatibility snapshot never rewrites or duplicates saved history") {
        withStorage(backend) { storage =>
          for {
            orchestrator <- make(storage)
            execution = new AgentExecution[Any] {
              val agentId: AgentId = ManagedHistorySubmitSuite.this.agentId
              private[orchestration] def run(
                  task: AgentTask,
                  history: ManagedHistory
              ): IO[YallmoError, AgentTurnResult] =
                for {
                  _ <- history.append(Chunk(MessagePayload.Assistant("canonical")))
                  active <- history.messages.map(_.map(_.payload))
                  returned = snapshot match {
                    case "empty"    => Chunk.empty
                    case "replaced" => Chunk(MessagePayload.User("replacement"))
                    case _          => active ++ active
                  }
                } yield AgentTurnResult(AgentResult(agentId, "done"), RunContext(returned))
            }
            result <- orchestrator.submit(sessionId, userId, AgentTask("accepted"), execution)
            stored <- storage.loadSession(sessionId)
          } yield {
            val expected = Chunk(MessagePayload.User("accepted"), MessagePayload.Assistant("canonical"))
            assertEquals(stored.messages.map(_.payload), expected)
            assertEquals(result.newMessages, expected)
            assertEquals(result.session, stored)
            assertEquals(stored.messages.map(_.agentId), Chunk(userId, agentId))
          }
        }
      }
    }

    for invalid <- List("truncated", "replaced", "author") do {
      test(
        s"$backend: returned $invalid snapshot cannot alter active history after failed persistence (invalid author rejected)"
      ) {
        withStorage(backend) { storage =>
          for {
            attempted <- Promise.make[Nothing, Unit]
            probe = new StorageProbe(storage) {
              override def putMessage(message: Message): IO[YallmoError, Message] =
                attempted.succeed(()) *> ZIO.fail(failure)
            }
            orchestrator <- make(probe)
            execution = new AgentExecution[Any] {
              val agentId: AgentId = ManagedHistorySubmitSuite.this.agentId
              private[orchestration] def run(
                  task: AgentTask,
                  history: ManagedHistory
              ): IO[YallmoError, AgentTurnResult] = history.messages.flatMap { messages =>
                val accepted = RunContext(messages.map(_.payload))
                val extended = accepted.append(MessagePayload.Assistant(task.prompt))
                val invalidContext = invalid match {
                  case "truncated" => accepted
                  case "replaced"  => accepted.append(MessagePayload.Assistant("replacement"))
                  case _           => extended
                }
                history.append(Chunk(MessagePayload.Assistant(task.prompt))) *> attempted.await.as(
                  AgentTurnResult(AgentResult(if invalid == "author" then userId else agentId, "bad"), invalidContext)
                )
              }
            }
            result <- orchestrator.submit(sessionId, userId, AgentTask("accepted"), execution).either
            stored <- storage.loadSession(sessionId)
          } yield {
            if invalid == "author" then
              assert(result.swap.toOption.get.error.isInstanceOf[YallmoError.InvalidAgentDecision])
            else
              assertEquals(
                result.toOption.get.newMessages,
                Chunk(MessagePayload.User("accepted"), MessagePayload.Assistant("accepted"))
              )
            assertEquals(stored.messages.map(_.payload), Chunk(MessagePayload.User("accepted")))
          }
        }
      }
    }
  }

  private final case class HistoryDecisionEvidence(context: String) derives JsonCodec

  private def assertHistoryDecisionWire(json: String): Unit = {
    val decoded = json.fromJson[SystemOneRequest[HistoryDecisionEvidence]]
    assert(decoded.isRight, decoded.toString)
    val request = decoded.toOption.get
    assertEquals(request.model, "jev-latest")
    assertEquals(request.questions.keySet, Set("rewrite"))
    assertEquals(
      request.questions("rewrite").asInstanceOf[SystemOneQuestion.Choice].criteria.keySet,
      Set("keep", "rebase")
    )
    assert(request.state.context.nonEmpty && request.state.context.length < 200)
    assert(!json.contains("SECRET"))
  }

  private def historyDecisionResponse(choice: String = "rebase", confidence: Double = 0.98): SystemOneResponse =
    SystemOneResponse(
      "jev-1.13.0",
      Map(
        "rewrite" -> SystemOneAnswer.Choice(
          choice,
          if choice == "keep" then Map("keep" -> 0.99, "rebase" -> 0.01) else Map("keep" -> 0.01, "rebase" -> 0.99),
          confidence
        )
      ),
      SystemOneUsage(32, 2)
    )

  private val reasoningPayload = MessagePayload.Reasoning(
    Some("SECRET-SUMMARY"),
    ProviderContinuation(ProviderId.unsafe("test"), Some("opaque-item"), "SECRET-CONTINUATION")
  )

  private def callPayload(id: String): MessagePayload =
    MessagePayload.ToolCall(FunctionCall(FunctionCallId.unsafe(id), toolKey, "{\"kept\":true}"))

  private def outputPayload(id: String, text: String = "result"): MessagePayload =
    MessagePayload.ToolOutput(FunctionGenericOutput(FunctionCallId.unsafe(id), toolKey, text))

  private def persistChain(storage: Storage, payloads: Chunk[MessagePayload]): IO[YallmoError, Chunk[Message]] =
    ZIO.foldLeft(payloads)(Chunk.empty[Message]) { (chain, payload) =>
      Clock.instant
        .flatMap(time => storage.putMessage(Message(MessageData(userId, chain.lastOption.map(_.id), time, payload))))
        .map(chain :+ _)
    }

  private def observingAgent(
      observed: Ref[Chunk[Chunk[Message]]],
      captured: Promise[Nothing, ManagedHistory]
  ): AgentInstance[Any] = new AgentInstance[Any] {
    val id: AgentId = agentId
    def next(task: AgentTask, context: StaticContext[Any], current: RunContext): IO[YallmoError, AgentTurn] =
      ZIO.dieMessage(s"Managed submission must use nextManaged: ${task.prompt}, ${context.systemPrompt}, $current")
    override def nextManaged(
        task: AgentTask,
        context: StaticContext[Any],
        current: RunContext,
        history: ManagedHistory
    ): IO[YallmoError, AgentTurn] =
      for {
        _ <- captured.succeed(history)
        messages <- history.messages
        _ <- observed.update(_ :+ messages)
        _ <- ZIO.succeed {
          assertEquals(messages.map(_.payload), current.messages)
          assertEquals(current.messages.count(_ == MessagePayload.User(task.prompt)), 1)
          assertEquals(context.tools.map(_.key), Chunk(toolKey))
        }
      } yield
        if current.messages.exists(_.isInstanceOf[MessagePayload.ToolOutput]) then finalTurn
        else
          AgentTurn(
            Some(MessagePayload.Assistant("calling")),
            AgentDecision.Actions(Chunk(LlmAction.CallExternalTool(ExternalToolCall(toolKey, "{}"))))
          )
  }

  private def toolWith(effect: IO[YallmoError, ExternalToolResult]): AgentTool[Any] = new AgentTool[Any] {
    val definition = AgentToolDefinition(toolKey, "Probe asynchronous persistence")
    def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
      ZIO.succeed(assertEquals(call.toolKey, toolKey)) *> effect
  }

  private def execution(agent: AgentInstance[Any], tool: AgentTool[Any]): AgentExecution[Any] =
    new AgentRunner[Any](Map(toolKey -> tool), Map.empty)
      .execution(agent, context.copy(tools = Chunk(tool.definition)))

  private def make(storage: Storage): IO[YallmoError, Orchestrator[Any]] = {
    val participant = new Agent[Any] {
      val id: AgentId = agentId
      def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
        ZIO.dieMessage(s"Only the supplied execution runs: ${request.model}")
    }
    for {
      instances <- RuntimeInstances.fromAgents(Chunk(participant))
      _ <- instances.registerUser(userId)
      orchestrator <- OrchestratorLive.make(storage, instances)
      _ <- orchestrator.createSession(sessionId, Chunk(userId))
    } yield orchestrator
  }

  private def withStorage(backend: String)(test: Storage => IO[Any, Unit]): Unit = {
    val root = Files.createTempDirectory("yallmo-managed-history-")
    try {
      val storage = if backend == "filesystem" then StorageFS.open(root) else StorageInMemory.make
      run(storage.flatMap(test).timeoutFail(YallmoError.InvalidConfig("Test did not make progress"))(10.seconds))
    } finally deleteRoot(root)
  }

  private def reload(storage: Storage): IO[YallmoError, Storage] = storage match {
    case fs: StorageFS => StorageFS.open(fs.root)
    case _             => ZIO.succeed(storage)
  }

  private def deleteRoot(root: Path): Unit = {
    val paths = Files.walk(root)
    try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
    finally paths.close()
  }

  private def run[E, A](effect: IO[E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def withLogs[E, A](logs: CopyOnWriteArrayList[String])(effect: IO[E, A]): IO[E, A] =
    ZIO.scoped {
      val logger = ZLogger.default.map(line => { logs.add(line); () })
      (Runtime.removeDefaultLoggers >>> Runtime.addLogger(logger)).build *> effect
    }

  /** Test-only fault injection at the independent object/publication seams, preserving the real backend semantics. */
  private class StorageProbe(delegate: Storage) extends Storage {
    def compareAndSetSessionHead(
        id: SessionId,
        expectedHead: Option[MessageId],
        newHead: Option[MessageId]
    ): IO[YallmoError, Session] =
      delegate.compareAndSetSessionHead(id, expectedHead, newHead)
    def listMessages = delegate.listMessages
    def putMessage(message: Message) = delegate.putMessage(message)
    def listBlobIds = delegate.listBlobIds
    def listSessions = delegate.listSessions
    def createSession(id: SessionId, agents: Chunk[AgentId]) = delegate.createSession(id, agents)
    def loadSession(id: SessionId) = delegate.loadSession(id)
    def loadSessionParcial(id: SessionId) = delegate.loadSessionParcial(id)
    def addSessionAgent(id: SessionId, agentId: AgentId) = delegate.addSessionAgent(id, agentId)
    def putMessagesAndUpdateSession(id: SessionId, head: Option[MessageId], messages: Chunk[Message]) =
      delegate.putMessagesAndUpdateSession(id, head, messages)
    def putBlob(blob: Blob) = delegate.putBlob(blob)
    def getBlob(id: BlobId) = delegate.getBlob(id)
    def fork(source: SessionId, target: SessionId) = delegate.fork(source, target)
    def readBlob(id: BlobId, timeout: Duration) = delegate.readBlob(id, timeout)
    def beginBlobUpload(id: BlobId, size: Long) = delegate.beginBlobUpload(id, size)
  }
}
