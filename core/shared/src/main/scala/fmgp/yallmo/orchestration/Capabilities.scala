package fmgp.yallmo.orchestration

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.RagDocument
import zio.*
import fmgp.yallmo.orchestration.{
  AgentToolDefinition,
  ExternalToolCall,
  ExternalToolResult,
  RagQuery,
  RetrieverDefinition
}

/** A trusted external capability that an agent may request through [[LlmAction.CallExternalTool]].
  *
  * The [[AgentRunner]] checks that the calling agent advertises this definition before invoking it. Implementations
  * must validate model-produced `argumentsJson` at their own boundary.
  */
trait AgentTool[-R] {
  def definition: AgentToolDefinition

  def execute(call: ExternalToolCall): ZIO[R, YallmoError, ExternalToolResult]
}

/** Validates untrusted provider JSON arguments against the schema advertised to that provider. */
private[orchestration] object ToolArgumentsValidator {
  def validate(argumentsJson: String, schema: fmgp.yallmo.model.llm.JsonSchema.ObjectSchema): Either[String, Unit] =
    scala.util
      .Try(ujson.read(argumentsJson))
      .toEither
      .left
      .map(_.getMessage)
      .flatMap(value => validateAt(value, schema, "$"))

  private def validateAt(
      value: ujson.Value,
      schema: fmgp.yallmo.model.llm.JsonSchema,
      path: String
  ): Either[String, Unit] =
    import fmgp.yallmo.model.llm.JsonSchema

    schema match
      case JsonSchema.StringSchema(_, allowedValues) =>
        value.strOpt.toRight(s"$path must be a string").flatMap { string =>
          Either.cond(
            allowedValues.isEmpty || allowedValues.contains(string),
            (),
            s"$path must be one of ${allowedValues.mkString(", ")}"
          )
        }
      case JsonSchema.NumberSchema(_)  => Either.cond(value.numOpt.nonEmpty, (), s"$path must be a number")
      case JsonSchema.IntegerSchema(_) =>
        Either.cond(value.numOpt.exists(_.isWhole), (), s"$path must be an integer")
      case JsonSchema.BooleanSchema(_)      => Either.cond(value.boolOpt.nonEmpty, (), s"$path must be a boolean")
      case JsonSchema.ArraySchema(items, _) =>
        value.arrOpt.toRight(s"$path must be an array").flatMap { values =>
          values.value.zipWithIndex.foldLeft[Either[String, Unit]](Right(())) { case (result, (item, index)) =>
            result.flatMap(_ => validateAt(item, items, s"$path[$index]"))
          }
        }
      case JsonSchema.ObjectSchema(properties, required, additionalProperties, _) =>
        value.objOpt.toRight(s"$path must be an object").flatMap { fields =>
          val missing = required.diff(fields.keySet)
          val unexpected = fields.keySet.diff(properties.keySet)
          if missing.nonEmpty then Left(s"$path is missing required properties: ${missing.toSeq.sorted.mkString(", ")}")
          else if !additionalProperties && unexpected.nonEmpty then
            Left(s"$path contains unsupported properties: ${unexpected.toSeq.sorted.mkString(", ")}")
          else
            properties.foldLeft[Either[String, Unit]](Right(())) { case (result, (name, propertySchema)) =>
              result.flatMap(_ => fields.get(name).fold(Right(()))(validateAt(_, propertySchema, s"$path.$name")))
            }
        }
}

/** A typed RAG boundary.
  *
  * Documents remain source-attributed [[RagDocument]] values until the receiving agent renders them for its model.
  */
trait KnowledgeRetriever[R] {
  def definition: RetrieverDefinition

  def retrieve(query: RagQuery): ZIO[R, YallmoError, Chunk[RagDocument]]
}
