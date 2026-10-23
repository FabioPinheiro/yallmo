package fmgp.yallmo

import fmgp.yallmo.model.{FunctionCallId, MessagePayload}
import fmgp.yallmo.model.llm.AgentTurnResponse
import zio.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Instant
import java.util.UUID

/** Best-effort native-turn diagnostics. Provider continuations are protected; audio diagnostics are built from blob
  * references.
  */
object LlmProviderPayloadDebug {
  private val LocalDirectory = Path.of("debug")
  private val Redacted = "<redacted>"
  private val SensitiveKeys = Set("encrypted_content", "encryptedContent")
  private val ScopedRequestCapture: FiberRef[Option[Path]] =
    Unsafe.unsafe { unsafe ?=> FiberRef.unsafe.make(None) }

  def newTurnId(): String = UUID.randomUUID().toString

  /** Runs an operation while retaining its most recent successfully captured provider request. */
  def withRequestCapture[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, (A, Option[Path])] =
    ScopedRequestCapture.locally(None)(effect.zip(ScopedRequestCapture.get))

  def captureRequest(turnId: String, provider: String, model: String, payload: ujson.Value): UIO[Unit] =
    if containsInlineAudio(payload) then
      ZIO.logWarning("provider debug capture skipped: use blob references instead of inline audio")
    else
      write(turnId, "request", ujson.Obj("provider" -> provider, "model" -> model, "payload" -> redact(payload)))
        .flatMap(path => ScopedRequestCapture.set(Some(path)))
        .catchAll(logFailure)

  def captureResponse(turnId: String, provider: String, model: String, response: AgentTurnResponse): UIO[Unit] =
    write(
      turnId,
      "response",
      ujson.Obj(
        "provider" -> provider,
        "model" -> model,
        "responseId" -> response.responseId.map(ujson.Str(_)).getOrElse(ujson.Null),
        "stopReason" -> response.stopReason.toString,
        "text" -> response.output.collect { case MessagePayload.Assistant(text) => text }.mkString,
        "toolCalls" -> ujson.Arr.from(
          response.output
            .collect { case MessagePayload.FunctionCallRequest(call) => call }
            .map(call =>
              ujson.Obj(
                "callId" -> FunctionCallId.value(call.id),
                "providerItemId" -> call.providerItemId.map(ujson.Str(_)).getOrElse(ujson.Null),
                "name" -> call.name,
                "arguments" -> call.argumentsJson
              )
            )
        )
      )
    ).unit.catchAll(logFailure)

  private def write(turnId: String, kind: String, document: ujson.Obj): Task[Path] =
    ZIO
      .attemptBlocking {
        Files.createDirectories(LocalDirectory)
        // Keep capture metadata first for quick inspection, and the potentially large wire payload last.
        val payload = document.value.get("payload")
        val captured = ujson.Obj("turnId" -> turnId, "capturedAt" -> Instant.now().toString)
        document.value.iterator
          .filterNot(_._1 == "payload")
          .foreach { case (key, value) =>
            captured(key) = value
          }
        payload.foreach(value => captured("payload") = value)
        val path = LocalDirectory.resolve(s"$turnId-$kind.json")
        Files.writeString(
          path,
          ujson.write(captured, indent = 2),
          UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING
        )
        path
      }

  private def logFailure(error: Throwable): UIO[Unit] =
    ZIO.logWarning(s"provider payload debug capture failed: ${error.getMessage}")

  /** Reject accidental wire-body capture rather than replacing or discarding content. */
  private def containsInlineAudio(value: ujson.Value): Boolean = value match {
    case obj: ujson.Obj =>
      obj.value.exists { case (key, child) =>
        (key == "input_audio" && child.objOpt.exists(_.contains("data"))) || containsInlineAudio(child)
      }
    case array: ujson.Arr => array.value.exists(containsInlineAudio)
    case _                => false
  }

  private[yallmo] def redact(value: ujson.Value): ujson.Value = value match
    case obj: ujson.Obj =>
      ujson.Obj.from(obj.value.iterator.map { case (key, child) =>
        key -> (if SensitiveKeys.contains(key) then ujson.Str(Redacted)
                else redact(child))
      })
    case array: ujson.Arr => ujson.Arr.from(array.value.map(redact))
    case other            => other

}
