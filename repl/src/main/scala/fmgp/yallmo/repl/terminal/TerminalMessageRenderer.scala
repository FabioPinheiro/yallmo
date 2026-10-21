package fmgp.yallmo.repl.terminal

import fmgp.yallmo.model.{AgentId, Message, MessagePayload}
import zio.*

private[terminal] object TerminalMessageRenderer {

  /** Renders stored history using each message's original author. */
  def render(messages: Chunk[Message]): Chunk[String] =
    messages.map(message => render(message.payload, Some(message.agentId)))

  /** Shared formatting for history and live output; unknown authors retain generic role labels. */
  def render(message: MessagePayload, author: Option[AgentId] = None): String =
    message match {
      case audio: MessagePayload.AudioAttachment =>
        s"${author.fold("user> ")(id => TerminalPrompt.user(id))}${audio.contentStr}"
      case MessagePayload.User(text) =>
        s"${author.fold("user> ")(id => TerminalPrompt.user(id))}$text"
      case MessagePayload.Assistant(text) =>
        s"${author.fold("assistant> ")(id => TerminalPrompt.assistant(id))}$text"
      case MessagePayload.RagResult(key, documents)  => s"retriever[$key]> ${documents.size} document(s)"
      case MessagePayload.ToolResult(key, text)      => s"tool[$key]> $text"
      case MessagePayload.FunctionCallRequest(call)  => s"tool-call[${call.name}]> ${call.argumentsJson}"
      case MessagePayload.FunctionCallOutput(output) => s"tool-output[${output.name}]> ${output.output}"
      case MessagePayload.Reasoning(summary, _)      => s"reasoning> ${summary.getOrElse("<provider continuation>")}"
      case MessagePayload.Context(label, text)       => s"context[$label]> $text"
      case MessagePayload.Instructions(text)         => s"system> $text"
    }
}
