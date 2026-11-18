package fmgp.yallmo.tool

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
    name: FunctionKey,
    argumentsJson: String,
    providerItemId: Option[String] = None
) derives JsonCodec {
  def validate: Either[String, FunctionCall] =
    Either.cond(name.value.trim.nonEmpty, this, "function call name must not be blank")
}

/** The trusted host's result for one [[FunctionCall]]. Providers project only [[output]]. */
sealed trait FunctionCallOutput {
  def callId: FunctionCallId
  def name: FunctionKey
  def output: String

  def validate: Either[String, FunctionCallOutput] =
    Either.cond(name.value.trim.nonEmpty, this, "function call output name must not be blank")
}

final case class FunctionGenericOutput(callId: FunctionCallId, name: FunctionKey, output: String)
    extends FunctionCallOutput

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

/** Host-measured elapsed times are milliseconds. Execution includes child startup/bootstrap, evaluation, process
  * cleanup and log collection (plus Scala CLI/run-stage compilation on JVM). The opt-in Node backend charges linking to
  * compilationTime. Total also includes source setup, diagnostics and directory cleanup. Unmeasured/skipped times
  * default to zero. executionSetupTime measures child launch until the host observes snippet evaluation starting
  * (including polling delay); None means evaluation was not observed, including older results. It is part of
  * executionTime, not additional.
  */
final case class ScalaModeOutput(
    callId: FunctionCallId,
    result: ScalaModeResult,
    logs: Seq[String] = Seq.empty,
    exitCode: Int = 0,
    truncated: Boolean = false,
    compilationTime: Long = 0L,
    executionTime: Long = 0L,
    totalTime: Long = 0L,
    executionSetupTime: Option[Long] = None
) extends FunctionCallOutput {
  def name: FunctionKey = FunctionKey.scalamode
  def output: String = result.output
}

object FunctionGenericOutput {
  private case class Fields(callId: FunctionCallId, name: FunctionKey, output: String) derives JsonCodec

  given JsonCodec[FunctionGenericOutput] = summon[JsonCodec[Fields]].transformOrFail(
    fields =>
      Either.cond(
        fields.name != FunctionKey.scalamode,
        FunctionGenericOutput(fields.callId, fields.name, fields.output),
        "scalamode requires ScalaModeOutput"
      ),
    output => Fields(output.callId, output.name, output.output)
  )
}

object ScalaModeOutput {
  private case class Fields(
      callId: FunctionCallId,
      name: FunctionKey,
      result: ScalaModeResult,
      logs: Seq[String] = Seq.empty,
      exitCode: Int = 0,
      // Read old persisted flags, but never duplicate timeout state in new output JSON.
      @jsonField("timedOut") legacyTimedOut: Option[Boolean] = None,
      truncated: Boolean = false,
      compilationTime: Long = 0L,
      executionTime: Long = 0L,
      totalTime: Long = 0L,
      executionSetupTime: Option[Long] = None
  ) derives JsonCodec

  given JsonCodec[ScalaModeOutput] = summon[JsonCodec[Fields]].transformOrFail(
    fields =>
      Either.cond(
        fields.name == FunctionKey.scalamode,
        ScalaModeOutput(
          fields.callId,
          if fields.legacyTimedOut.contains(true) then ScalaModeResult.TimedOut(fields.result.output)
          else fields.result,
          fields.logs,
          fields.exitCode,
          fields.truncated,
          fields.compilationTime,
          fields.executionTime,
          fields.totalTime,
          fields.executionSetupTime
        ),
        "ScalaModeOutput requires name=scalamode"
      ),
    output =>
      Fields(
        output.callId,
        FunctionKey.scalamode,
        output.result,
        output.logs,
        output.exitCode,
        legacyTimedOut = None,
        output.truncated,
        output.compilationTime,
        output.executionTime,
        output.totalTime,
        output.executionSetupTime
      )
  )
}

object FunctionCallOutput {
  private case class Discriminator(name: FunctionKey) derives JsonCodec

  // The existing tool key is the only discriminator. Reserved-key decoding never falls back to generic output.
  given JsonCodec[FunctionCallOutput] = summon[JsonCodec[Json]].transformOrFail(
    json =>
      json.as[Discriminator].flatMap { discriminator =>
        if discriminator.name == FunctionKey.scalamode then json.as[ScalaModeOutput]
        else json.as[FunctionGenericOutput]
      },
    output =>
      (output match {
        case value: ScalaModeOutput       => value.toJsonAST
        case value: FunctionGenericOutput => value.toJsonAST
      }).fold(error => throw new IllegalStateException(error), identity)
  )
}
