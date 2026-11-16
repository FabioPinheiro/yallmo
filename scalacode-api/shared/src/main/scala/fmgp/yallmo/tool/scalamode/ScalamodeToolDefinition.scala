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
      |// Trace is provided by the runner. No ambient filesystem, network, process or environment access.
      |""".stripMargin

  val Default: FunctionToolDefinition = FunctionToolDefinition(
    name = Execute,
    description = "Evaluate a Scala block with Scala 3.10.1, offline. The last expression is returned. " +
      "Returned ZIO effects are executed by the host. Automatically imported restricted ZIO constructors: " +
      "ZIO.succeed, fail, attempt, fromEither, foreach and unit (not the full zio.ZIO companion). " +
      "Example: ZIO.succeed(1 + 1) returns 2." +
      "Trusted API, automatically imported: def api: Unit — prints Tool API available. Also available as tool.api. " +
      "No direct file, network, process, environment access or println. " +
      "No user-supplied dependencies, directives or annotations. " +
      "Returns only the value on success, or actionable compiler/runtime diagnostics on failure. " +
      "The host retains typed results, exitCode, timedOut, truncated and separate execution logs.",
    inputSchema = JsonSchema.ObjectSchema(
      Map("code" -> JsonSchema.StringSchema(Some("Scala block body; its last expression is the result"))),
      required = Set("code"),
      additionalProperties = false
    ),
    scalamode = Some(ScalamodeApi(BuiltinApi))
  )

  /** Same function identity and code-only schema; only the explicitly bound API is advertised. */
  def withServices(apiDocs: String): FunctionToolDefinition = Default.copy(
    description = Default.description +
      " This binding supplies the following trusted API for code-only calls: " + apiDocs,
    scalamode = None // Legacy prose-only binding: do not misreport it as a pure binding with no services.
  )

  /** Carries exactly the same interface documentation to the model description and the read-only inspector. */
  def withServices(service: ScalamodeServiceApi): FunctionToolDefinition = withServices(List(service))

  def withServices(services: List[ScalamodeServiceApi]): FunctionToolDefinition = {
    val docs = services
      .map(service => service.description + service.scalaApi.fold("")(source => s"\nScala API:\n$source"))
      .mkString("\n")
    withServices(docs).copy(scalamode = Some(ScalamodeApi(BuiltinApi, services)))
  }

  override val name: ToolKey = ToolKey.Scalamode
  override val functions: Set[FunctionToolDefinition] = Set(Default)
}
