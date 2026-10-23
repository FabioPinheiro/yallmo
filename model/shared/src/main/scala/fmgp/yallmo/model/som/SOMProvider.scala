package fmgp.yallmo.model.som

import fmgp.yallmo.model.YallmoError
import zio.{IO, ZIO}
import zio.json.{JsonCodec, JsonEncoder}

/** Provider-neutral System One evaluation interface.
  *
  * System One is a constrained decision protocol, not a text-generation or agent-turn API. Provider adapters implement
  * [[evaluateRequestJson]] for their HTTP transport; these public overloads own JSON encoding and, for a fixed schema,
  * response-to-answer validation.
  *
  * A System One model is part of each request, rather than provider-wide default configuration, so this interface has
  * no `defaultModel` equivalent to [[fmgp.yallmo.model.llm.LlmProvider]].
  *
  * TODO REVIEW consider rename the methods from evaluate to 'inferDecision' or just 'infer'
  */
trait SOMProvider {

  /** Evaluates a request with runtime-defined question IDs.
    *
    * The returned response is decoded at the provider boundary but is otherwise unvalidated against the request. Use
    * the typed overload when the question schema is known statically.
    */
  def evaluate[S](request: SystemOneRequest[S])(using JsonCodec[S]): IO[YallmoError, SystemOneResponse] =
    evaluateRequestJson(JsonEncoder[SystemOneRequest[S]].encodeJson(request).toString)

  /** Evaluates a fixed question schema and validates the result before exposing typed answer fields.
    *
    * A response which does not satisfy `SOSchema[Q]` fails with [[YallmoError.ProtocolFailure]]. More detailed System
    * One semantic validation is intentionally separate future work; see `docs/system_one/JEV_models.md`.
    */
  def evaluate[S, Q](
      request: SORequest[S, Q]
  )(using
      stateEncoder: JsonEncoder[S],
      questionEncoder: JsonEncoder[Q],
      schema: SOSchema[Q]
  ): IO[YallmoError, SOAnswer[Q]] =
    evaluateRequestJson(JsonEncoder[SORequest[S, Q]].encodeJson(request).toString).flatMap { response =>
      ZIO.fromEither(schema.decodeAnswer(response).left.map(YallmoError.ProtocolFailure.apply))
    }

  /** Performs one provider-specific request using a fully encoded System One envelope.
    *
    * Implementations are responsible for transport, authentication, HTTP-status handling, and decoding the successful
    * response into [[SystemOneResponse]]. They must map failures to [[YallmoError]] and must not log secrets.
    */
  protected def evaluateRequestJson(requestJson: String): IO[YallmoError, SystemOneResponse]
}
