package fmgp.yallmo.repl.voice

import fmgp.yallmo.model.{AgentId, AudioAgent, AudioInput, MessagePayload, ToolChoice, YallmoError}
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, LlmStopReason, ProviderGenerateOptions}
import fmgp.yallmo.orchestration.{Orchestrator, SessionId}
import fmgp.yallmo.orchestration.config.ConfiguredAgentSettings
import zio.*

/** REPL service invoking the configured audio agent and retaining its transcript before editable draft insertion. */
trait Transcriber {
  def transcribe(audio: AudioInput): IO[YallmoError, String]
}

object Transcriber {
  val DisabledMessage = "Voice transcription is disabled: configure transcriberAgent with an audio-capable agent."

  def make(
      agent: AudioAgent[Any],
      settings: ConfiguredAgentSettings,
      orchestrator: Orchestrator[Any],
      humanId: AgentId
  ): Transcriber = new Transcriber {
    def transcribe(audio: AudioInput): IO[YallmoError, String] =
      for {
        response <- agent.turn(
          AgentTurnRequest(
            model = settings.model,
            input = Chunk(audio, MessagePayload.Instructions(settings.systemPrompt)),
            tools = Chunk.empty,
            toolChoice = ToolChoice.None,
            parallelToolCalls = false,
            options = ProviderGenerateOptions(responseMimeType = None, thinkingLevel = settings.thinkingLevel)
          )
        )
        transcript <- ZIO.fromEither(extract(response))
        // No draft can receive a successful result before durable retention; never attach this thread to chat state.
        _ <- (for {
          uuid <- Random.nextUUID
          id = SessionId.unsafe(s"transcription-$uuid")
          _ <- orchestrator.createSession(id, Chunk(humanId, agent.id))
          // Storage must hash the live input before retaining its metadata-only projection.
          _ <- orchestrator.append(id, humanId, Chunk(audio))
          _ <- orchestrator.append(id, agent.id, Chunk(MessagePayload.Assistant(transcript)))
        } yield ()).uninterruptible.mapError(_ =>
          YallmoError.InvalidConfig(
            "Voice transcript could not be retained; draft was not changed. Check session storage."
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
