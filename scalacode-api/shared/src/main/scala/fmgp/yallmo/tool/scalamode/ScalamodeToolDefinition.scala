package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.model.llm.JsonSchema
import fmgp.yallmo.tool.*

/** Stable callable identifiers and membership declared by this tool, independent of its runtime bindings. */
object ScalamodeToolDefinition extends ToolDefinition {
  val Execute: FunctionKey = FunctionKey.scalamode

  /** Public signatures of the restricted imports, not privileged implementations or the full ZIO companion. */
  val BuiltinApi: String =
    """// Automatically imported: import fmgp.yallmo.tool.scalamode.{ScalaToolApi as tool}; import tool.*
      |object tool {
      |  type ZIO[-R, +E, +A] = zio.ZIO[R, E, A]
      |  def api: Unit // Prints a fixed API greeting; also callable as tool.api
      |  object ZIO {
      |    def succeed[A](value: => A)(using zio.Trace): zio.UIO[A]
      |    def fail[E](error: => E)(using zio.Trace): zio.IO[E, Nothing]
      |    def attempt[A](value: => A)(using zio.Trace): zio.Task[A]
      |    def fromEither[E, A](value: => Either[E, A])(using zio.Trace): zio.IO[E, A]
      |    def foreach[A, R, E, B](values: Iterable[A])(f: A => zio.ZIO[R, E, B])(using zio.Trace): zio.ZIO[R, E, List[B]]
      |    def unit(using zio.Trace): zio.UIO[Unit]
      |  }
      |}
      |""".stripMargin

  val Default: FunctionToolDefinition = FunctionToolDefinition(
    name = Execute,
    description = "Evaluate a Scala block with Scala 3.10.1. The last expression is returned. " +
      "Returned ZIO effects are executed by the host. Automatically imported restricted ZIO constructors: " +
      "ZIO.succeed, fail, attempt, fromEither, foreach and unit (not the full zio.ZIO companion). " +
      "Example: ZIO.succeed(1 + 1) returns 2." +
      "Trusted API, automatically imported: def api: Unit — prints Tool API available. Also available as tool.api. " +
      "No direct file, network, process, environment access or println. ",
    inputSchema = JsonSchema.ObjectSchema(
      Map("code" -> JsonSchema.StringSchema(Some("Scala block body; its last expression is the result"))),
      required = Set("code"),
      additionalProperties = false
    ),
    scalamode = Some(ScalamodeApi(BuiltinApi))
  )

  def withServices(services: Seq[ScalamodeServiceApi]): FunctionToolDefinition = {
    val apiDocs = services
      .map(service => service.description + service.scalaApi.fold("")(source => s"\nScala API:\n$source"))
      .mkString("\n")
    Default.copy(
      description = Default.description +
        " This binding supplies the following trusted API for code-only calls: " + apiDocs,
      scalamode = Some(
        ScalamodeApi(BuiltinApi, services)
      ) // Legacy prose-only binding: do not misreport it as a pure binding with no services.
    )

  }

  override val name: ToolKey = ToolKey.Scalamode
  override val functions: Set[FunctionToolDefinition] = Set(Default)
}
