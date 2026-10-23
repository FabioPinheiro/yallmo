package fmgp.yallmo.provider.gemini

import com.google.genai.types.{
  Content,
  FunctionDeclaration,
  GenerateContentConfig,
  MediaResolution as SdkMediaResolution,
  Part,
  Schema,
  ThinkingConfig,
  Tool,
  Type
}
import scala.jdk.CollectionConverters.*
import zio.Chunk

import fmgp.yallmo.model.llm.{
  LlmToolDefinition,
  MediaResolution,
  ProviderGenerateOptions,
  ThinkingLevel,
  ToolSchemaType
}

object GenAiMappers {

  def toGenerateContentConfig(
      options: ProviderGenerateOptions
  ): Either[GenAiError.InvalidConfig, GenerateContentConfig] =
    toGenerateContentConfig(options, Chunk.empty)

  def toGenerateContentConfig(
      options: ProviderGenerateOptions,
      tools: Chunk[LlmToolDefinition]
  ): Either[GenAiError.InvalidConfig, GenerateContentConfig] =
    for {
      _ <- options.validate.left.map(GenAiError.InvalidConfig.apply)
      _ <- validateTools(tools).left.map(GenAiError.InvalidConfig.apply)
    } yield {
      var builder = GenerateContentConfig.builder()

      options.systemInstruction.foreach { instruction =>
        builder = builder.systemInstruction(Content.fromParts(Part.fromText(instruction)))
      }
      options.temperature.foreach(v => builder = builder.temperature(v.toFloat))
      options.topP.foreach(v => builder = builder.topP(v.toFloat))
      options.topK.foreach(v => builder = builder.topK(v.toFloat))
      options.candidateCount.foreach(v => builder = builder.candidateCount(v))
      options.maxOutputTokens.foreach(v => builder = builder.maxOutputTokens(v))
      if options.stopSequences.nonEmpty then builder = builder.stopSequences(options.stopSequences.toList*)
      options.responseMimeType.foreach(v => builder = builder.responseMimeType(v))
      options.seed.foreach(v => builder = builder.seed(v))
      options.thinkingLevel.foreach { level =>
        builder = builder.thinkingConfig(ThinkingConfig.builder().thinkingLevel(toSdkThinkingLevel(level)).build())
      }
      options.mediaResolution.foreach { resolution =>
        builder = builder.mediaResolution(SdkMediaResolution(toSdkMediaResolution(resolution)))
      }
      if tools.nonEmpty then builder = builder.tools(toSdkTool(tools))

      builder.build()
    }

  private def toSdkTool(definitions: Chunk[LlmToolDefinition]): Tool =
    Tool
      .builder()
      .functionDeclarations(definitions.map(toSdkFunctionDeclaration).toList*)
      .build()

  private def toSdkFunctionDeclaration(definition: LlmToolDefinition): FunctionDeclaration =
    FunctionDeclaration
      .builder()
      .name(definition.name)
      .description(definition.description)
      .parameters(toSdkParametersSchema(definition.parameters))
      .build()

  private def toSdkParametersSchema(parameters: Chunk[fmgp.yallmo.model.llm.LlmToolParameter]): Schema = {
    val properties = parameters
      .map { parameter =>
        parameter.name -> Schema
          .builder()
          .`type`(toSdkType(parameter.schemaType))
          .description(parameter.description)
          .build()
      }
      .toMap
      .asJava

    val requiredNames = parameters.filter(_.required).map(_.name).toList

    var builder = Schema
      .builder()
      .`type`(Type.Known.OBJECT)
      .properties(properties)

    if requiredNames.nonEmpty then builder = builder.required(requiredNames.asJava)
    builder.build()
  }

  private def toSdkThinkingLevel(level: ThinkingLevel): String = level match
    case ThinkingLevel.Minimal => "minimal"
    case ThinkingLevel.Low     => "low"
    case ThinkingLevel.Medium  => "medium"
    case ThinkingLevel.High    => "high"

  private def toSdkMediaResolution(resolution: MediaResolution): String = resolution match
    case MediaResolution.Low    => "MEDIA_RESOLUTION_LOW"
    case MediaResolution.Medium => "MEDIA_RESOLUTION_MEDIUM"
    case MediaResolution.High   => "MEDIA_RESOLUTION_HIGH"

  private def toSdkType(schemaType: ToolSchemaType): Type = Type(schemaType.toString.toUpperCase)

  private def validateTools(tools: Chunk[LlmToolDefinition]): Either[String, Unit] =
    tools.foldLeft[Either[String, Unit]](Right(())) { (acc, tool) =>
      acc.flatMap(_ => tool.validate)
    }

  def textFromResponse(
      response: com.google.genai.types.GenerateContentResponse
  ): Either[GenAiError.EmptyResponse, String] =
    Option(response.text()).map(_.trim).filter(_.nonEmpty).toRight(GenAiError.EmptyResponse())
}
