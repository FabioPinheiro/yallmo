package fmgp.yallmo.tool.sdlc

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.tool.{FunctionToolDefinition, FunctionKey}

import fmgp.yallmo.model.tool.SdlcToolDefinition

import fmgp.yallmo.fs.{FsError, FsRead, FsWrite}
import fmgp.yallmo.orchestration.{AgentFunction, AgentTool, ExternalFunctionCall, ExternalFunctionResult}
import fmgp.yallmo.tool.sdlc.llm.{PlanIntentSimplifiedModel, PlanTaskSimplifiedModel}
import zio.*
import zio.json.*

/** Agent-facing planning tools over the same server-owned service used by the HTTP inspector. */
object SdlcTools {
  private[sdlc] val MaxArgumentBytes = 128 * 1024
  val SetDefinition: FunctionToolDefinition = SdlcToolDefinition.SetDefinition
  val ReadTaskDefinition: FunctionToolDefinition = SdlcToolDefinition.ReadTaskDefinition
  val SetIntentsDefinition: FunctionToolDefinition = SdlcToolDefinition.SetIntentsDefinition
  val ReadIntentDefinition: FunctionToolDefinition = SdlcToolDefinition.ReadIntentDefinition
  val ListDefinition: FunctionToolDefinition = SdlcToolDefinition.ListDefinition
  val Definitions: Chunk[FunctionToolDefinition] =
    Chunk(SetDefinition, ReadTaskDefinition, SetIntentsDefinition, ReadIntentDefinition, ListDefinition)

  /** Convenience for embedded users; the standalone server passes its already-created shared service instead. */
  def make(read: FsRead, write: FsWrite): UIO[AgentTool[Any]] =
    SdlcService.make(read, write).map(service => make(service))

  /** Bind the same service to tools and HTTP routes; this does not create another storage owner. */
  def make(service: SdlcService): AgentTool[Any] = AgentTool(SdlcToolDefinition, makeFunctions(service))

  def makeFunctions(read: FsRead, write: FsWrite): UIO[Chunk[AgentFunction[Any]]] =
    make(read, write).map(_.functions)

  def makeFunctions(service: SdlcService): Chunk[AgentFunction[Any]] =
    Definitions.map { toolDefinition =>
      new AgentFunction[Any] {
        override val definition: FunctionToolDefinition = toolDefinition

        override def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] = {
          def invalid(message: String) = YallmoError.InvalidToolArguments(definition.name, message)
          for {
            _ <- ZIO.fail(invalid("Mismatched tool name")).unless(call.functionKey == definition.name)
            _ <- ZIO
              .fail(invalid(s"Arguments must not exceed $MaxArgumentBytes UTF-8 bytes"))
              .when(
                call.argumentsJson.length > MaxArgumentBytes || call.argumentsJson
                  .getBytes("UTF-8")
                  .length > MaxArgumentBytes
              )
            _ <- ZIO
              .fromEither(FunctionToolDefinition.validateArgument(call.argumentsJson, definition.inputSchema))
              .mapError(invalid)
            arguments <- ZIO.attempt(ujson.read(call.argumentsJson)).mapError(error => invalid(error.getMessage))
            result <- SdlcTools.execute(service, definition.name, arguments).mapError {
              case FsError.InvalidArgument(message) => invalid(message)
              case error                            =>
                YallmoError.ToolExecutionFailed(
                  definition.name,
                  error.toString,
                  new java.io.IOException(error.toString)
                )
            }
          } yield ExternalFunctionResult(result)
        }
      }
    }

  private def execute(service: SdlcService, key: FunctionKey, arguments: ujson.Value): IO[FsError, String] = {
    def decode[A: JsonDecoder](field: String, default: ujson.Value = ujson.Null): IO[FsError, A] =
      ZIO
        .fromEither(ujson.write(arguments.obj.getOrElse(field, default)).fromJson[A])
        .mapError(FsError.InvalidArgument.apply)

    key match {
      case SdlcToolDefinition.SetTasks =>
        decode[List[PlanTaskSimplifiedModel]]("tasksJson").flatMap(service.setTasks).map(_.toJson)
      case SdlcToolDefinition.ReadTask   => decode[List[String]]("taskIds").flatMap(service.readTasks).map(_.toJson)
      case SdlcToolDefinition.SetIntents =>
        decode[List[PlanIntentSimplifiedModel]]("intentsJson").flatMap(service.setIntents).map(_.toJson)
      case SdlcToolDefinition.ReadIntent => decode[List[String]]("intentIds").flatMap(service.readIntents).map(_.toJson)
      case SdlcToolDefinition.List       =>
        for {
          query <- decode[String]("query", ujson.Str(""))
          archived <- decode[Boolean]("includeArchived", ujson.Bool(false))
          limit <- decode[Int]("limit", ujson.Num(50))
          after <- decode[Option[String]]("afterId")
          page <- service.discover(query, archived, limit, after)
        } yield page.toJson
      case _ => ZIO.fail(FsError.InvalidArgument("Unsupported SDLC operation"))
    }
  }
}
