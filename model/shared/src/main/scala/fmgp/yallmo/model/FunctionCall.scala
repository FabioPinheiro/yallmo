package fmgp.yallmo.model

import zio.json.*
import zio.json.ast.Json

/** Stable link between one model-requested function call and its trusted result. */
opaque type FunctionCallId = String

object FunctionCallId {
  def from(value: String): Either[String, FunctionCallId] =
    Either.cond(value.trim.nonEmpty, value, "function call id must not be blank")

  def unsafe(value: String): FunctionCallId = value

  def value(id: FunctionCallId): String = id

  given JsonCodec[FunctionCallId] = JsonCodec.string.transformOrFail(FunctionCallId.from, FunctionCallId.value)
}

/** A function invocation proposed by the model. It is data until the trusted runner validates and executes it. */
final case class FunctionCall(
    id: FunctionCallId,
    name: ToolKey,
    argumentsJson: String,
    providerItemId: Option[String] = None
) derives JsonCodec {
  def validate: Either[String, FunctionCall] =
    Either.cond(name.value.trim.nonEmpty, this, "function call name must not be blank")
}

/** The trusted host's result for one [[FunctionCall]]. Providers project only [[output]]. */
sealed trait FunctionCallOutput {
  def callId: FunctionCallId
  def name: ToolKey
  def output: String

  def validate: Either[String, FunctionCallOutput] =
    Either.cond(name.value.trim.nonEmpty, this, "function call output name must not be blank")
}

final case class FunctionGenericOutput(callId: FunctionCallId, name: ToolKey, output: String) extends FunctionCallOutput

/** CompilationError includes compiler setup/launch-stage diagnostics. TimedOut covers the shared compilation/execution
  * deadline. Executed is an attempt outcome, not a success guarantee: inspect exitCode.
  */
enum ScalaModeResult derives JsonCodec {
  case CompilationError(diagnostics: String)
  case Executed(value: String)
  case TimedOut(diagnostics: String)

  def output: String = this match {
    case CompilationError(diagnostics) => diagnostics
    case Executed(value)               => value
    case TimedOut(diagnostics)         => diagnostics
  }
}

/** Host-measured elapsed times are milliseconds. Execution includes Scala CLI/JVM startup and any run-stage
  * compilation; total also includes source setup, diagnostics and cleanup. Unmeasured/skipped times default to zero.
  */
final case class ScalaModeOutput(
    callId: FunctionCallId,
    result: ScalaModeResult,
    logs: Seq[String] = Seq.empty,
    exitCode: Int = 0,
    truncated: Boolean = false,
    compilationTime: Long = 0L,
    executionTime: Long = 0L,
    totalTime: Long = 0L
) extends FunctionCallOutput {
  def name: ToolKey = ToolKey.Scalamode
  def output: String = result.output
}

object FunctionGenericOutput {
  private case class Fields(callId: FunctionCallId, name: ToolKey, output: String) derives JsonCodec

  given JsonCodec[FunctionGenericOutput] = summon[JsonCodec[Fields]].transformOrFail(
    fields =>
      Either.cond(
        fields.name != ToolKey.Scalamode,
        FunctionGenericOutput(fields.callId, fields.name, fields.output),
        "scalamode requires ScalaModeOutput"
      ),
    output => Fields(output.callId, output.name, output.output)
  )
}

object ScalaModeOutput {
  private case class Fields(
      callId: FunctionCallId,
      name: ToolKey,
      result: ScalaModeResult,
      logs: Seq[String] = Seq.empty,
      exitCode: Int = 0,
      // Read old persisted flags, but never duplicate timeout state in new output JSON.
      @jsonField("timedOut") legacyTimedOut: Option[Boolean] = None,
      truncated: Boolean = false,
      compilationTime: Long = 0L,
      executionTime: Long = 0L,
      totalTime: Long = 0L
  ) derives JsonCodec

  given JsonCodec[ScalaModeOutput] = summon[JsonCodec[Fields]].transformOrFail(
    fields =>
      Either.cond(
        fields.name == ToolKey.Scalamode,
        ScalaModeOutput(
          fields.callId,
          if fields.legacyTimedOut.contains(true) then ScalaModeResult.TimedOut(fields.result.output)
          else fields.result,
          fields.logs,
          fields.exitCode,
          fields.truncated,
          fields.compilationTime,
          fields.executionTime,
          fields.totalTime
        ),
        "ScalaModeOutput requires name=scalamode"
      ),
    output =>
      Fields(
        output.callId,
        ToolKey.Scalamode,
        output.result,
        output.logs,
        output.exitCode,
        legacyTimedOut = None,
        output.truncated,
        output.compilationTime,
        output.executionTime,
        output.totalTime
      )
  )
}

object FunctionCallOutput {
  private case class Discriminator(name: ToolKey) derives JsonCodec

  // The existing tool key is the only discriminator. Reserved-key decoding never falls back to generic output.
  given JsonCodec[FunctionCallOutput] = summon[JsonCodec[Json]].transformOrFail(
    json =>
      json.as[Discriminator].flatMap { discriminator =>
        if discriminator.name == ToolKey.Scalamode then json.as[ScalaModeOutput]
        else json.as[FunctionGenericOutput]
      },
    output =>
      (output match {
        case value: ScalaModeOutput       => value.toJsonAST
        case value: FunctionGenericOutput => value.toJsonAST
      }).fold(error => throw new IllegalStateException(error), identity)
  )
}
