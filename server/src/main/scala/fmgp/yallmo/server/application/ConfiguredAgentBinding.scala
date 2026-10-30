package fmgp.yallmo.server.application

import fmgp.yallmo.model.{Agent, AgentId, Blob, BlobId, MessagePayload, ToolChoice, YallmoError}
import fmgp.yallmo.model.llm.AgentTurnRequest
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{AgentCapabilities, ProviderId}
import zio.*
import fmgp.yallmo.fs.FsRead

/** Configured execution binding; non-model agents do not decode native generation settings. */
enum ConfiguredAgentBinding(val adapter: Agent[Any], val systemPrompt: String) {
  case Native(
      override val adapter: Agent[Any],
      providerId: ProviderId,
      program: NativeFunctionAgent[Agent[Any]],
      override val systemPrompt: String,
      override val filesystem: Option[FsRead] = None
  ) extends ConfiguredAgentBinding(adapter, systemPrompt)

  case Folder(
      override val adapter: Agent[Any],
      policy: AgentCapabilities,
      override val filesystem: Option[FsRead] = None
  ) extends ConfiguredAgentBinding(adapter, "")

  def filesystem: Option[FsRead]

  def bind[R](readBlob: BlobId => IO[YallmoError, Blob]): AgentInstance[R] = this match {
    case Native(adapter, _, program, _, _) => program.bind[R](adapter, readBlob)
    case Folder(adapter, policy, _)        =>
      new AgentInstance[R] {
        override val id: AgentId = adapter.id
        // Direct tool invocation bypasses next, so the no-tool policy must also live on the binding.
        override val capabilities: AgentCapabilities = policy.copy(tools = Some(Set.empty))

        override def next(
            task: AgentTask,
            context: StaticContext[R],
            current: RunContext
        ): IO[YallmoError, AgentTurn] = {
          // The shared turn envelope has a model field, but folder execution neither uses nor validates it.
          val request = AgentTurnRequest(
            model = "",
            input = current.messages,
            toolChoice = ToolChoice.None,
            readBlob = readBlob
          )
          for {
            response <- adapter.turn(request)
            text = response.output.collect { case MessagePayload.Assistant(value) => value }.mkString
            _ <- ZIO
              .fail(YallmoError.InvalidAgentDecision("FolderStructureAgent must return a non-blank text answer"))
              .unless(text.trim.nonEmpty && response.output.forall(_.isInstanceOf[MessagePayload.Assistant]))
          } yield AgentTurn(Some(MessagePayload.Assistant(text)), AgentDecision.FinalAnswer(text))
        }
      }
  }
}
