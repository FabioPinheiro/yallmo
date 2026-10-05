package fmgp.yallmo.orchestration

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{LlmProvider, ProviderGenerateOptions}
import zio.*
import fmgp.yallmo.orchestration.{AgentDecision, AgentTask, AgentTurn, RunContext, StaticContext}

/** The two text values sent to a text-generation provider for one agent turn. */
final case class AgentPrompt(
    systemInstruction: String,
    prompt: String
)

/** A participant in an agent run.
  *
  * An agent proposes a decision; it never executes tools or retrieval itself. Those side effects belong to
  * [[AgentRunner]].
  */
trait AgentInstance[R] {
  def id: AgentId

  def next(
      task: AgentTask,
      staticContext: StaticContext[R],
      runContext: RunContext
  ): ZIO[R, YallmoError, AgentTurn]

}

/** Decodes one text-model response into the next trusted orchestration decision.
  *
  * A production decoder can use structured JSON output or provider-native function calls. Keeping it separate from the
  * agent makes provider protocol parsing testable and prevents the runtime from executing unparsed model text.
  */
trait AgentDecisionCodec {
  def instructions: String

  def decode(response: String, staticContext: StaticContext[?]): IO[YallmoError, AgentDecision]
}

/** An LLM-backed agent whose model response is decoded into an [[AgentDecision]]. */
final case class LlmDecisionAgent(
    id: AgentId,
    decisionCodec: AgentDecisionCodec,
    options: ProviderGenerateOptions = ProviderGenerateOptions.fast,
    model: Option[String] = None
) extends AgentInstance[LlmProvider] {

  override def next(
      task: AgentTask,
      staticContext: StaticContext[LlmProvider],
      runContext: RunContext
  ): ZIO[LlmProvider, YallmoError, AgentTurn] =
    val agentPrompt = LlmDecisionAgent.renderPrompt(task, staticContext, runContext, decisionCodec)
    ZIO
      .serviceWithZIO[LlmProvider](
        _.inferenceWithText(
          agentPrompt.prompt,
          options.copy(systemInstruction = Some(agentPrompt.systemInstruction)),
          model
        )
      )
      .mapError(error => YallmoError.AgentExecutionFailed(id, error.getMessage, error))
      .flatMap(response =>
        decisionCodec.decode(response, staticContext).map(AgentTurn(Some(MessagePayload.Assistant(response)), _))
      )
}

object LlmDecisionAgent {
  private[orchestration] def renderPrompt(
      task: AgentTask,
      staticContext: StaticContext[?],
      runContext: RunContext,
      decisionCodec: AgentDecisionCodec
  ): AgentPrompt =
    AgentPrompt(
      systemInstruction(staticContext, decisionCodec),
      renderTask(task, staticContext, runContext)
    )

  private[orchestration] def systemInstruction(
      staticContext: StaticContext[?],
      decisionCodec: AgentDecisionCodec
  ): String =
    Chunk(
      staticContext.systemPrompt,
      staticContext.skills.map(_.instructions).mkString("\n\n"),
      decisionCodec.instructions
    ).filter(_.nonEmpty).mkString("\n\n")

  private[orchestration] def renderTask(
      task: AgentTask,
      staticContext: StaticContext[?],
      runContext: RunContext
  ): String =
    val history = runContext.messages.flatMap {
      case MessagePayload.User(text)                         => Chunk(s"User: $text")
      case MessagePayload.Assistant(text)                    => Chunk(s"Assistant: $text")
      case MessagePayload.RagResult(retrieverKey, documents) =>
        val renderedDocuments = documents
          .map { document =>
            val score = document.score.fold("")(value => s", score=$value")
            s"id=${document.id}, source=${document.source}$score\n${document.text}"
          }
          .mkString("\n\n")
        Chunk(s"RAG result from $retrieverKey:\n$renderedDocuments")
      case MessagePayload.ToolResult(toolKey, text)  => Chunk(s"Tool result from $toolKey:\n$text")
      case MessagePayload.FunctionCallRequest(call)  => Chunk(s"Function call ${call.name}: ${call.argumentsJson}")
      case MessagePayload.FunctionCallOutput(output) => Chunk(s"Function output from ${output.name}:\n${output.output}")
      case MessagePayload.Reasoning(summary, _) => Chunk(s"Reasoning: ${summary.getOrElse("<provider continuation>")}")
      case MessagePayload.Context(label, text)  => Chunk(s"$label:\n$text")
      case MessagePayload.Instructions(text)    => Chunk(s"Instructions: $text")
    }
    val capabilities = Chunk(
      staticContext.tools.map(tool => s"Tool: ${tool.key} — ${tool.description}").mkString("\n"),
      staticContext.retrievers
        .map(retriever => s"Retriever: ${retriever.key} — ${retriever.description}")
        .mkString("\n"),
      staticContext.skills.map(skill => s"Skill: ${skill.key} — ${skill.description}").mkString("\n")
    ).filter(_.nonEmpty)

    (capabilities ++ history :+ s"Task: ${task.prompt}").mkString("\n\n")
}
