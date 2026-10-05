package fmgp.yallmo.repl.terminal

import fmgp.yallmo.model.MessagePayload
import zio.*

private[terminal] object TerminalMessageRenderer {
  def render(messages: Chunk[MessagePayload]): Chunk[String] =
    messages.map {
      case MessagePayload.User(text)                 => s"user> $text"
      case MessagePayload.Assistant(text)            => s"assistant> $text"
      case MessagePayload.RagResult(key, documents)  => s"retriever[$key]> ${documents.size} document(s)"
      case MessagePayload.ToolResult(key, text)      => s"tool[$key]> $text"
      case MessagePayload.FunctionCallRequest(call)  => s"tool-call[${call.name}]> ${call.argumentsJson}"
      case MessagePayload.FunctionCallOutput(output) => s"tool-output[${output.name}]> ${output.output}"
      case MessagePayload.Reasoning(summary, _)      => s"reasoning> ${summary.getOrElse("<provider continuation>")}"
      case MessagePayload.Context(label, text)       => s"context[$label]> $text"
      case MessagePayload.Instructions(text)         => s"system> $text"
    }
}
