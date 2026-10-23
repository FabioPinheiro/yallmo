package fmgp.yallmo.orchestration

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.som.{SOAnswer, SOMProvider, SORequest, SOSchema}
import zio.ZIO
import zio.json.JsonEncoder

/** Draft typed System One decision component.
  *
  * Unlike [[AgentInstance]], this is not a text-producing participant in [[AgentRunner]]. It evaluates a closed
  * question schema and returns typed decision data for deterministic host policy to interpret.
  */
final case class SystemOneDecisionAgent[S, Q](model: String, questions: S => Q) {

  /** Builds the request for `state` and delegates evaluation to the configured [[SOMProvider]]. */
  def decide(state: S)(using JsonEncoder[S], JsonEncoder[Q], SOSchema[Q]): ZIO[SOMProvider, YallmoError, SOAnswer[Q]] =
    ZIO.serviceWithZIO[SOMProvider](_.evaluate(SORequest(state, model, questions(state))))
}
