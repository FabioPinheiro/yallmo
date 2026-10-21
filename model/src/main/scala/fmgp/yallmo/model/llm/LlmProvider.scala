package fmgp.yallmo.model.llm

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.{Blob, BlobId, MessagePayload}
import zio.*

/** Prompt-based audio inference capability. Structured turns belong to audio-capable agents. */
trait LlmProviderAudio:
  /** Performs inference using a text prompt and referenced audio resolved through the supplied read-only reader.
    *
    * TODO improve the readBlob / use streams
    */
  def inferenceWithAudio(
      prompt: String,
      audio: MessagePayload.AudioAttachment,
      model: Option[String] = None,
      readBlob: BlobId => IO[YallmoError, Blob] = Blob.unavailable
  ): IO[YallmoError, String]

trait LlmProviderImage:
  /** Performs LLM inference using a text prompt and image input to generate a response. */
  def inferenceWithImage(
      prompt: String,
      image: Chunk[Byte],
      format: String,
      model: Option[String] = None
  ): IO[YallmoError, String]

trait LlmProviderText:
  /** Performs LLM inference using a text prompt and generation options to generate a response. */
  def inferenceWithText(
      prompt: String,
      options: ProviderGenerateOptions,
      model: Option[String]
  ): IO[YallmoError, String]

  @deprecated("Use Agent with NativeFunctionAgent so AgentRunner owns tool execution.", "0.2.0")
  def inferenceWithTextAndTools[R](
      prompt: String,
      tools: Chunk[LlmTool[R]], // TODO use Seq
      options: ProviderGenerateOptions,
      model: Option[String]
  ): ZIO[R, YallmoError, String]

/** Provider-neutral text-generation interface implemented by each provider adapter. */
trait LlmProvider extends LlmProviderText {
  def defaultModel: String

  // REVIEW name
  override def inferenceWithText(
      prompt: String,
      options: ProviderGenerateOptions,
      model: Option[String]
  ): IO[YallmoError, String]

  @deprecated("Use Agent with NativeFunctionAgent so AgentRunner owns tool execution.", "0.2.0")
  override def inferenceWithTextAndTools[T](
      prompt: String,
      tools: Chunk[LlmTool[T]],
      options: ProviderGenerateOptions,
      model: Option[String]
  ): ZIO[T, YallmoError, String] = generateWithTools(prompt, tools, options, model)

  @deprecated("Use Agent with NativeFunctionAgent so AgentRunner owns tool execution.", "0.2.0")
  def generateWithTools[R](
      prompt: String,
      tools: Chunk[LlmTool[R]],
      options: ProviderGenerateOptions,
      model: Option[String]
  ): ZIO[R, YallmoError, String]
}
