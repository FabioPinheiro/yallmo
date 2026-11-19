package fmgp.yallmo.tool.scalamode

import fmgp.rpc.RpcDispatcher
import fmgp.yallmo.tool.ScalamodeServiceApi

/** Host-authored descriptor, not a ZIO environment. API implementations and runners are compiled in reviewed narrow
  * artifacts. Nothing is derived from R at runtime; binding metadata and transport are never model arguments.
  */
trait ScalamodeService {
  def apiDocs: String

  /** Public Scala interface declarations for this exact API view; never host implementations or transport details. */
  def scalaApi: Option[String] = None

  /** Safe documentation projection shared by the model's advertisement and the agent tool inspector. */
  final def inspection: ScalamodeServiceApi =
    ScalamodeServiceApi(binding.parameter, binding.apiTypes.toList, apiDocs, scalaApi)

  /** All advertised parameters, including reviewed pure singleton services supplied alongside the primary API. */
  final def inspections: List[ScalamodeServiceApi] = inspection :: binding.constants.toList

  def binding: ScalamodeService.Binding
  def rpcBinding: Option[ScalamodeService.RpcBinding] = None

  /** Executor-provided loopback transport with invocation-scoped authority. The live layer shares its listener across
    * executions; no listener is opened during service construction.
    */
  def localRpcBinding: Option[RpcDispatcher] = None
}

object ScalamodeService {

  /** Reviewed products are platform-owned; JVM anchors never stand in for Scala.js IR. Paths are host grants. */
  final case class Products(
      jvm: Seq[Class[?]] = Seq.empty,
      scalaJs: Option[Seq[String]] = None,
      scalaJsDependencies: Map[String, String] = Map.empty
  )

  /** Names only, never source programs. The runner is a compiled method accepting a typed API => ZIO[Any, E, A]
    * callback and printing its result. API views are intersected; singleton views may end in .type. The error type
    * supplies safe-mode CanFail evidence. Additional artifact anchors locate only reviewed API/runtime JARs or classes
    * directories, never core/server or the application classpath. Dependencies must be pinned and cached offline.
    */
  final case class Binding(
      parameter: String,
      apiTypes: Seq[String],
      runner: String,
      products: Products = Products(),
      errorType: String = "java.lang.Throwable",
      dependencies: Seq[String] = Seq.empty,
      constants: Seq[ScalamodeServiceApi] = Seq.empty
  ) {
    private val keywords = Set(
      "abstract",
      "case",
      "catch",
      "class",
      "def",
      "do",
      "else",
      "enum",
      "export",
      "extends",
      "false",
      "final",
      "finally",
      "for",
      "forSome",
      "given",
      "if",
      "implicit",
      "import",
      "lazy",
      "match",
      "new",
      "null",
      "object",
      "override",
      "package",
      "private",
      "protected",
      "return",
      "sealed",
      "super",
      "then",
      "this",
      "throw",
      "trait",
      "true",
      "try",
      "type",
      "val",
      "var",
      "while",
      "with",
      "yield",
      "_"
    )
    private def identifier(value: String): Boolean =
      value.matches("[A-Za-z_][A-Za-z0-9_]*") && !keywords.contains(value)
    private def qualified(value: String): Boolean =
      value.contains('.') && value.split("\\.", -1).forall(identifier)
    require(
      identifier(parameter) && !Set(
        "tool",
        "zio",
        "using",
        "ScalaToolSnippet",
        "ScalaToolRuntime",
        "Trace",
        "CanFail",
        "summon"
      ).contains(parameter),
      "Invalid Scalamode parameter identifier"
    )
    require(
      apiTypes.nonEmpty && apiTypes.forall(value => qualified(value.stripSuffix(".type"))),
      "Invalid Scalamode API type name"
    )
    constants.foreach { constant =>
      require(identifier(constant.parameter), "Invalid Scalamode constant identifier")
      require(
        constant.apiTypes.size == 1 && constant.apiTypes.head.endsWith(".type") &&
          qualified(constant.apiTypes.head.stripSuffix(".type")),
        "Scalamode constants must be qualified singleton API types"
      )
      // Reuse the primary parameter's reserved-name validation.
      Binding(constant.parameter, constant.apiTypes, runner)
    }
    require(
      (parameter +: constants.map(_.parameter)).distinct.size == constants.size + 1,
      "Duplicate Scalamode parameter identifiers"
    )
    require(qualified(runner), "Invalid Scalamode runner name")
    require(qualified(errorType), "Invalid Scalamode error type name")
  }

  /** Caller owns the listener. The executor registers this exact dispatcher for one scoped child and sends endpoint and
    * a fresh ticket on stdin, never in source, command arguments or URLs. Compiled runtime code consumes the bootstrap.
    */
  final case class RpcBinding(endpoint: String, route: ScalamodePlatform.RpcRoute, dispatcher: RpcDispatcher)
}
