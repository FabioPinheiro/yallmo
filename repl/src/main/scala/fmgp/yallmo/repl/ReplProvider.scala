package fmgp.yallmo.repl

import fmgp.yallmo.model.{Agent, AgentId, YallmoError}
import fmgp.yallmo.model.llm.{AudioInput, LlmProviderAudio}
import fmgp.yallmo.provider.Transcriber
import fmgp.yallmo.provider.oai.{OAIAgent, OAILlmProvider}
import fmgp.yallmo.provider.openai.OpenAiCodexClient
import zio.*
import zio.http.Client

/** The selected native agent and model; no credentials are read until an explicit model turn. */
private[repl] final case class ReplProvider(agent: Agent[Any], model: String, selection: ReplProviderSelection)

private[repl] object ReplProvider {

  /** Shared by both live adapters and offline composition tests. */
  def selected(options: ReplLaunchOptions, agent: Agent[Any], defaultModel: String): ReplProvider =
    ReplProvider(agent, options.model.getOrElse(defaultModel), options.provider)

  def layer(options: ReplLaunchOptions): ZLayer[Client, Throwable, ReplProvider] =
    options.provider match {
      case ReplProviderSelection.Oai =>
        ZLayer
          .makeSome[Client, OAIAgent & OAILlmProvider](
            OAILlmProvider.live,
            OAIAgent.live(AgentId("Gamma"))
          )
          .map { environment =>
            ZEnvironment(
              selected(options, environment.get[OAIAgent], environment.get[OAILlmProvider].defaultModel)
            )
          }
      case ReplProviderSelection.ChatGpt =>
        OpenAiCodexClient.live.map { environment =>
          val provider = environment.get[OpenAiCodexClient]
          ZEnvironment(selected(options, provider, provider.defaultModel))
        }
    }

  /** Voice remains independently OAI-backed; missing voice configuration must not block subscription text chat. */
  val transcription: ZLayer[Client, Nothing, Transcriber] =
    (OAILlmProvider.live >>> Transcriber.live).catchAll(_ => ZLayer.succeed(unavailableTranscriber))

  private[repl] def unavailableTranscriber: Transcriber =
    new Transcriber(new LlmProviderAudio {
      override def inferenceWithAudio(
          prompt: String,
          audio: AudioInput,
          model: Option[String]
      ): IO[YallmoError, String] = {
        val _ = (prompt, audio, model)
        ZIO.fail(
          YallmoError.InvalidConfig(
            "Voice transcription requires a separate audio-capable OAI endpoint: set MY_MODEL_URL and MY_MODEL_NAME. " +
              "ChatGPT subscription mode provides text/tool turns only."
          )
        )
      }
    })
}
