package fmgp.yallmo

import fmgp.yallmo.model.llm.ProviderGenerateOptions
import zio.*
import zio.json.{EncoderOps, JsonCodec}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Instant
import java.util.UUID

/** Best-effort local debug capture for LLM requests and raw outputs.
  *
  * Capture failures are logged and never fail the LLM request.
  */
object LlmDebugCapture {
  private val LocalDirectory = Path.of("debug")

  /** Records the request before a provider call so timeouts retain useful local diagnostics. */
  def captureCallStarted(
      provider: String,
      model: String,
      prompt: String,
      options: ProviderGenerateOptions,
      toolNames: Seq[String]
  ): UIO[LlmDebugCall] = {
    val call = LlmDebugCall(
      id = UUID.randomUUID().toString,
      startedAt = Instant.now().toString,
      provider = provider,
      model = model,
      prompt = prompt,
      options = options,
      toolNames = toolNames
    )

    ZIO.logInfo(
      s"LLM call started provider=$provider model=$model tools=${toolNames.mkString("[", ",", "]")} promptChars=${prompt.length}"
    ) *>
      writeNewCall(call).catchAll(logFailure("local LLM request debug capture", _)).as(call)
  }

  def captureCallSucceeded(call: LlmDebugCall, output: String): UIO[Unit] =
    ZIO.logInfo(
      s"LLM call succeeded provider=${call.provider} model=${call.model} callId=${call.id} outputChars=${output.length}"
    ) *>
      updateCall(call.copy(completedAt = Some(Instant.now().toString), outcome = "succeeded", output = output))
        .catchAll(logFailure("local LLM debug capture", _))

  def captureCallFailed(call: LlmDebugCall, error: Throwable): UIO[Unit] = {
    val message = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
    ZIO.logError(s"LLM call failed provider=${call.provider} model=${call.model} callId=${call.id}: $message") *>
      updateCall(
        call.copy(completedAt = Some(Instant.now().toString), outcome = "failed", output = "", error = Some(message))
      ).catchAll(logFailure("local LLM debug capture", _))
  }

  private def writeNewCall(call: LlmDebugCall): Task[Unit] =
    write(call, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

  private def updateCall(call: LlmDebugCall): Task[Unit] =
    write(call, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)

  private def write(call: LlmDebugCall, options: StandardOpenOption*): Task[Unit] =
    ZIO.attemptBlocking {
      Files.createDirectories(LocalDirectory)
      Files.writeString(callPath(call), call.toJsonPretty, StandardCharsets.UTF_8, options*)
      ()
    }

  private def callPath(call: LlmDebugCall): Path =
    LocalDirectory.resolve(s"${call.startedAt.replaceAll("[^0-9A-Za-z.-]", "-")}-${call.id}.json")

  private def logFailure(label: String, error: Throwable): UIO[Unit] =
    ZIO.logWarning(s"$label failed: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}")
}

final case class LlmDebugCall(
    id: String,
    startedAt: String,
    provider: String,
    model: String,
    prompt: String,
    options: ProviderGenerateOptions,
    toolNames: Seq[String],
    completedAt: Option[String] = None,
    outcome: String = "started",
    output: String = "",
    error: Option[String] = None
) derives JsonCodec
