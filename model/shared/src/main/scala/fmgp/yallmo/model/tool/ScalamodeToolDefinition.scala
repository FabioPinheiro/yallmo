package fmgp.yallmo.model.tool

import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition, ToolDefinition, ToolKey}
import fmgp.yallmo.model.llm.JsonSchema
import zio.Chunk

/** Stable callable identifiers and membership declared by this tool, independent of its runtime bindings. */
object ScalamodeToolDefinition extends ToolDefinition {
  val Execute: FunctionKey = FunctionKey.unsafe("scalamode")

  val Pure: FunctionToolDefinition = FunctionToolDefinition(
    name = Execute,
    description = "Evaluate a Scala block with Scala 3.10.1, offline. The last expression is returned. " +
      "Returned ZIO effects are executed by the host. Automatically imported restricted ZIO constructors: " +
      "ZIO.succeed, fail, attempt, fromEither, foreach and unit (not the full zio.ZIO companion). " +
      "Example: ZIO.succeed(1 + 1) returns 2. Trace is supplied; do not import zio.ZIO. " +
      "Trusted API, automatically imported: def api: Unit — prints Tool API available. Also available as tool.api. " +
      "No direct file, network, process, environment access or println. " +
      "No user-supplied dependencies, directives or annotations. " +
      "Returns only the value on success, or actionable compiler/runtime diagnostics on failure. " +
      "The host retains typed results, exitCode, timedOut, truncated and separate execution logs.",
    inputSchema = JsonSchema.ObjectSchema(
      Map("code" -> JsonSchema.StringSchema(Some("Scala block body; its last expression is the result"))),
      required = Set("code"),
      additionalProperties = false
    )
  )

  val Filesystem: FunctionToolDefinition = Pure.copy(
    description = Pure.description +
      " Optional mode=filesystem: code must return ZIO[Any, FsError, A], with selected read-only fs: FsRead injected. " +
      "Example: fs.readUtf8(\"notes.txt\").map(_.length). " +
      "Supports fs.list and fs.readBytes; use fold for error recovery. Trace and CanFail are supplied. " +
      "No runtime execution in code; trusted Main connects once and runs the effect. Default mode=pure is unchanged.",
    inputSchema = JsonSchema.ObjectSchema(
      Map(
        "code" -> JsonSchema.StringSchema(
          Some("Pure block, or filesystem-mode effect body returning ZIO[Any, FsError, A]")
        ),
        "mode" -> JsonSchema.StringSchema(Some("Explicit result mode; defaults to pure"), Chunk("pure", "filesystem"))
      ),
      required = Set("code"),
      additionalProperties = false
    )
  )

  override val name: ToolKey = ToolKey.Scalamode
  override val functions: Set[FunctionToolDefinition] = Set(Pure)
}
