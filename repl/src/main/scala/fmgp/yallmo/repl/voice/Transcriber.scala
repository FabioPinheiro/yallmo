package fmgp.yallmo.repl.voice

import fmgp.yallmo.model.{AgentId, AudioAgent, MessagePayload, ToolChoice, YallmoError}
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, LlmStopReason, ProviderGenerateOptions}
import fmgp.yallmo.orchestration.{Orchestrator, SessionId}
import fmgp.yallmo.orchestration.config.ConfiguredAgentSettings
import zio.*

/** REPL service invoking the configured audio agent and retaining its transcript before editable draft insertion. */
trait Transcriber {
  def transcribe(audio: CapturedAudio): IO[YallmoError, String]
}

object Transcriber {
  val DisabledMessage = "Voice transcription is disabled: configure transcriberAgent with an audio-capable agent."

  // Audio transcription intentionally retains its separate single-provider-turn ingestion path.
  @scala.annotation.nowarn("cat=deprecation")
  def make(
      agent: AudioAgent[Any],
      settings: ConfiguredAgentSettings,
      orchestrator: Orchestrator[Any],
      humanId: AgentId
  ): Transcriber = new Transcriber {
    def transcribe(audio: CapturedAudio): IO[YallmoError, String] =
      for {
        uuid <- Random.nextUUID
        id = SessionId.unsafe(s"transcription-$uuid")
        _ <- (orchestrator.createSession(id, Chunk(humanId, agent.id)) *>
          orchestrator.appendWithBlobBytes(id, humanId, audio.input, audio.bytes))
          .mapError(_ =>
            YallmoError.InvalidConfig("Voice audio could not be stored; draft was not changed. Check session storage.")
          )
        response <- orchestrator.turn(
          agent.id,
          AgentTurnRequest(
            model = settings.model,
            input = Chunk(audio.input, MessagePayload.Instructions(settings.systemPrompt)),
            tools = Chunk.empty,
            toolChoice = ToolChoice.None,
            parallelToolCalls = false,
            options = ProviderGenerateOptions(responseMimeType = None, thinkingLevel = settings.thinkingLevel)
          )
        )
        transcript <- ZIO.fromEither(extract(response))
        _ <- orchestrator
          .append(id, agent.id, Chunk(MessagePayload.Assistant(transcript)))
          .mapError(_ =>
            YallmoError.InvalidConfig(
              "Voice transcript could not be stored; draft was not changed. Check session storage."
            )
          )
      } yield transcript
  }

  private def extract(response: AgentTurnResponse): Either[YallmoError, String] = {
    val texts = response.output.collect { case MessagePayload.Assistant(text) => text }
    if response.stopReason != LlmStopReason.Stop || texts.size != response.output.size then
      Left(YallmoError.InvalidConfig("Voice transcription returned an unsupported response; no actions were executed."))
    else {
      val transcript = texts.mkString("\n").trim
      Either.cond(
        transcript.nonEmpty,
        transcript,
        YallmoError.InvalidConfig("Voice input produced no transcript.")
      )
    }
  }
}
