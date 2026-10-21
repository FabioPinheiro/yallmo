package fmgp.yallmo.repl.debug

import fmgp.yallmo.model.MessagePayload
import fmgp.yallmo.model.llm.value
import upickle.default.*
import zio.*

/** Debug information retained by the REPL without initiating a model request. */
final case class ReplDebugSnapshot(messages: Chunk[MessagePayload])

/** Renders JSONL transcript diagnostics. Persisting or displaying that data is a client responsibility. */
object ReplDebug {
  def renderJsonLines(messages: Chunk[MessagePayload], timestamp: Long = java.lang.System.currentTimeMillis()): String =
    if messages.isEmpty then ""
    else messages.map(message => upickle.default.write(toJson(message, timestamp))).mkString("", "\n", "\n")

  private def toJson(message: MessagePayload, timestamp: Long): ujson.Obj =
    message match
      case audio: MessagePayload.AudioAttachment             => textMessage("user", audio.contentStr, timestamp)
      case MessagePayload.User(text)                         => textMessage("user", text, timestamp)
      case MessagePayload.Assistant(text)                    => textMessage("assistant", text, timestamp)
      case MessagePayload.RagResult(retrieverKey, documents) =>
        ujson.Obj(
          "role" -> "ragResult",
          "retrieverKey" -> retrieverKey,
          "documents" -> ujson.Arr.from(
            documents.map(document =>
              ujson.Obj(
                "id" -> document.id,
                "source" -> document.source,
                "text" -> document.text,
                "score" -> document.score.map(ujson.Num(_)).getOrElse(ujson.Null)
              )
            )
          ),
          "timestamp" -> ujson.Num(timestamp.toDouble)
        )
      case MessagePayload.ToolResult(toolKey, text) =>
        ujson.Obj(
          "role" -> "toolResult",
          "toolName" -> toolKey,
          "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> text)),
          "timestamp" -> ujson.Num(timestamp.toDouble)
        )
      case MessagePayload.FunctionCallRequest(call) =>
        ujson.Obj(
          "role" -> "function_call",
          "callId" -> fmgp.yallmo.model.value(call.id),
          "name" -> call.name,
          "arguments" -> call.argumentsJson,
          "timestamp" -> ujson.Num(timestamp.toDouble)
        )
      case MessagePayload.FunctionCallOutput(output) =>
        ujson.Obj(
          "role" -> "function_call_output",
          "callId" -> fmgp.yallmo.model.value(output.callId),
          "name" -> output.name,
          "output" -> output.output,
          "timestamp" -> ujson.Num(timestamp.toDouble)
        )
      case MessagePayload.Reasoning(summary, continuation) =>
        ujson.Obj(
          "role" -> "reasoning",
          "provider" -> continuation.provider.value,
          "itemId" -> continuation.itemId.map(ujson.Str(_)).getOrElse(ujson.Null),
          "summary" -> summary.map(ujson.Str(_)).getOrElse(ujson.Null),
          "encryptedContent" -> "<redacted>",
          "timestamp" -> ujson.Num(timestamp.toDouble)
        )
      case MessagePayload.Context(label, text) =>
        ujson.Obj(
          "role" -> "context",
          "label" -> label,
          "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> text)),
          "timestamp" -> ujson.Num(timestamp.toDouble)
        )
      case MessagePayload.Instructions(text) => textMessage("system", text, timestamp)

  private def textMessage(role: String, text: String, timestamp: Long): ujson.Obj =
    ujson.Obj(
      "role" -> role,
      "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> text)),
      "timestamp" -> ujson.Num(timestamp.toDouble)
    )
}
