package fmgp.yallmo.orchestration

import fmgp.yallmo.fs.FsRead
import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.config.{ConfiguredAgentSettings, ProviderId}
import zio.*

/** One immutable configured generation: program, effective policy/context and its bound implementations travel
  * together.
  */
final case class ResolvedAgent private[orchestration] (
    program: AgentInstance[Any],
    context: StaticContext[Any],
    functions: Chunk[AgentFunction[Any]],
    adapter: Agent[Any],
    filesystem: Option[FsRead],
    providerId: Option[ProviderId],
    settings: Option[ConfiguredAgentSettings]
) {
  def tools: Chunk[AgentTool[Any]] = AgentTool.group(context.tools, functions)
  def id: AgentId = program.id
  def systemPrompt: String = context.systemPrompt
}

/** Provider-free folder execution also denies direct tool invocations, which do not call next. */
object FolderAgentProgram {
  def apply(
      adapter: Agent[Any],
      policy: fmgp.yallmo.orchestration.config.AgentCapabilities,
      readBlob: BlobId => IO[YallmoError, Blob]
  ): AgentInstance[Any] = new AgentInstance[Any] {
    val id: AgentId = adapter.id
    override val capabilities = policy.copy(tools = Some(Set.empty))
    def next(task: AgentTask, context: StaticContext[Any], current: RunContext): IO[YallmoError, AgentTurn] =
      for {
        response <- adapter.turn(
          fmgp.yallmo.model.llm
            .AgentTurnRequest(model = "", input = current.messages, readBlob = readBlob)
        )
        text = response.output.collect { case MessagePayload.Assistant(value) => value }.mkString
        _ <- ZIO
          .fail(YallmoError.InvalidAgentDecision("FolderStructureAgent must return a non-blank text answer"))
          .unless(text.trim.nonEmpty && response.output.forall(_.isInstanceOf[MessagePayload.Assistant]))
      } yield AgentTurn(Some(MessagePayload.Assistant(text)), AgentDecision.FinalAnswer(text))
  }
}
