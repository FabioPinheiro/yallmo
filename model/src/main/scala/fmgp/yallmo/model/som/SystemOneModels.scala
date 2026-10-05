package fmgp.yallmo.model.som

import zio.Chunk
import zio.json.{JsonCodec, jsonDiscriminator, jsonField, jsonHint}

/** Wire model for one `POST /v1/systemone` request.
  *
  * This is the System One evaluation protocol used by TypeSafe Jev and the compatible local Laya service. It is
  * deliberately separate from chat-completions and agent-turn models: System One evaluates one state against typed
  * questions and returns constrained answers; it does not generate an assistant message or tool calls.
  *
  * Use this map-backed model when question IDs are dynamic, for example when eligible workers are assembled at runtime.
  * For a fixed, application-owned question case class with compile-time answer-field access, use [[SORequest]] together
  * with `derives SOSchema`; it encodes to the same `questions` JSON object.
  *
  * @param state
  *   Material to evaluate. `State` is caller-defined so the application owns a type-safe state case class and its
  *   [[zio.json.JsonCodec]]. The common simple state is `String`.
  * @param model
  *   Required official TypeSafe model identifier, normally `jev-latest`. The local Laya service currently tolerates an
  *   omitted field, but callers should retain this required field for the documented API contract.
  * @param questions
  *   Questions keyed by caller-owned identifiers. Answers in [[SystemOneResponse]] use the same keys.
  */
final case class SystemOneRequest[State](
    state: State,
    model: String,
    questions: Map[String, SystemOneQuestion]
) derives JsonCodec

/** A typed System One question serialized with its protocol `type` discriminator.
  *
  * Question IDs are the keys in [[SystemOneRequest.questions]], rather than fields on this hierarchy. The API supports
  * structured instructions and criteria, but this first typed model intentionally represents their normal text form.
  */
@jsonDiscriminator("type")
sealed trait SystemOneQuestion derives JsonCodec {
  def instructions: String
}

object SystemOneQuestion {

  /** Asks the service to choose exactly one caller-defined option.
    *
    * @param instructions
    *   The judgment to make about the request state.
    * @param criteria
    *   Closed option IDs and their descriptions. A successful answer can select only one of these IDs.
    */
  @jsonHint("choice")
  final case class Choice(instructions: String, criteria: Map[String, String]) extends SystemOneQuestion
      derives JsonCodec

  /** Asks the service to place the state on an ordered rubric.
    *
    * @param instructions
    *   The judgment to make about the request state.
    * @param criteria
    *   Ordered level descriptions. The returned score may be fractional between levels.
    */
  @jsonHint("score")
  final case class Score(instructions: String, criteria: Seq[String]) extends SystemOneQuestion derives JsonCodec

  /** Asks whether a statement is true of the state.
    *
    * @param instructions
    *   The yes/no judgment to make about the request state.
    * @param criteria
    *   Optional descriptions of the true and false meanings. Omit when the statement is already unambiguous.
    */
  @jsonHint("noul")
  final case class Noul(instructions: String, criteria: Option[SystemOneNoulCriteria] = None) extends SystemOneQuestion
      derives JsonCodec
}

/** Optional clarification for the two outcomes of a Noul question.
  *
  * The API uses the literal JSON member names `true` and `false`; Scala names remain descriptive and are mapped by the
  * codec.
  */
final case class SystemOneNoulCriteria(
    @jsonField("true") whenTrue: String,
    @jsonField("false") whenFalse: String
) derives JsonCodec

/** Wire model for one successful `POST /v1/systemone` response.
  *
  * @param model
  *   Versioned model identifier that evaluated the request. It can differ from the requested alias, for example when
  *   `jev-latest` resolves to a particular Jev release; Laya returns its own model identifier.
  * @param answers
  *   Typed answers keyed by the same caller-owned IDs supplied in [[SystemOneRequest.questions]].
  * @param usage
  *   Token accounting returned by the service. It contains the official fields and optional Laya extensions.
  * @param routing
  *   Optional Laya routing diagnostics. Official Jev does not document this member.
  */
final case class SystemOneResponse(
    model: String,
    answers: Map[String, SystemOneAnswer],
    usage: SystemOneUsage,
    routing: Option[SystemOneRouting] = None
) derives JsonCodec

/** A typed System One answer serialized with its protocol `type` discriminator.
  *
  * Choice and Score answers carry service-calculated confidence. A Noul's official result is its yes probability; Laya
  * additionally returns confidence metadata, represented as optional fields below.
  */
@jsonDiscriminator("type")
sealed trait SystemOneAnswer derives JsonCodec

object SystemOneAnswer {

  /** Answer to a [[SystemOneQuestion.Choice]].
    *
    * @param choice
    *   highest-probability criterion ID.
    * @param probabilities
    *   probability for every submitted criterion ID.
    * @param confidence
    *   official Jev confidence derived from the probability distribution.
    * @param answerConfidence
    *   Laya extension: probability of the selected answer.
    * @param action
    *   Laya extension describing its action probability.
    * @param xJevConfidence
    *   Laya extension retaining its Jev-style confidence metric.
    */
  @jsonHint("choice")
  final case class Choice(
      choice: String,
      probabilities: Map[String, Double],
      confidence: Double,
      @jsonField("answer_confidence") answerConfidence: Option[Double] = None,
      action: Option[SystemOneAction] = None,
      @jsonField("x_jev_confidence") xJevConfidence: Option[Double] = None
  ) extends SystemOneAnswer derives JsonCodec

  /** Answer to a [[SystemOneQuestion.Score]].
    *
    * @param score
    *   probability-weighted position on the submitted ordered rubric.
    * @param legend
    *   service echo of numeric level IDs and descriptions.
    * @param probabilities
    *   probability for each numeric level ID.
    * @param confidence
    *   official Jev confidence derived from the probability distribution.
    * @param answerConfidence
    *   Laya extension: probability of its peak rubric level.
    * @param action
    *   Laya extension describing its action probability.
    * @param xJevConfidence
    *   Laya extension retaining its Jev-style confidence metric.
    */
  @jsonHint("score")
  final case class Score(
      score: Double,
      legend: Map[String, String],
      probabilities: Map[String, Double],
      confidence: Double,
      @jsonField("answer_confidence") answerConfidence: Option[Double] = None,
      action: Option[SystemOneAction] = None,
      @jsonField("x_jev_confidence") xJevConfidence: Option[Double] = None
  ) extends SystemOneAnswer derives JsonCodec

  /** Answer to a [[SystemOneQuestion.Noul]].
    *
    * @param noul
    *   probability that the submitted statement is true, from 0 to 1.
    * @param confidence
    *   optional Laya extension; it is not part of the documented Jev Noul response.
    * @param answerConfidence
    *   optional Laya extension for the selected answer probability.
    * @param action
    *   optional Laya action metadata.
    * @param xJevConfidence
    *   optional Laya Jev-style confidence metadata when supplied by a future Laya response.
    */
  @jsonHint("noul")
  final case class Noul(
      noul: Double,
      confidence: Option[Double] = None,
      @jsonField("answer_confidence") answerConfidence: Option[Double] = None,
      action: Option[SystemOneAction] = None,
      @jsonField("x_jev_confidence") xJevConfidence: Option[Double] = None
  ) extends SystemOneAnswer derives JsonCodec
}

/** Laya extension attached to an answer when it estimates that the host should act automatically. */
final case class SystemOneAction(@jsonField("act_probability") actProbability: Double) derives JsonCodec

/** Token accounting from a System One response.
  *
  * `input_tokens` and `output_tokens` are documented by official Jev. The remaining members were observed in Laya and
  * have defaults so an official response decodes without them.
  *
  * @param stateTokens
  *   number of tokens consumed by state after Laya processing, when reported.
  * @param stateTokensDropped
  *   number of state tokens Laya discarded, when reported.
  * @param truncated
  *   whether Laya truncated request material.
  * @param truncatedQuestions
  *   question IDs or descriptions Laya truncated.
  */
final case class SystemOneUsage(
    @jsonField("input_tokens") inputTokens: Int,
    @jsonField("output_tokens") outputTokens: Int,
    @jsonField("state_tokens") stateTokens: Option[Int] = None,
    @jsonField("state_tokens_dropped") stateTokensDropped: Option[Int] = None,
    truncated: Boolean = false,
    @jsonField("truncated_questions") truncatedQuestions: Chunk[String] = Chunk.empty
) derives JsonCodec

/** Laya-only diagnostic information describing how it routed a request to an internal model.
  *
  * This metadata is observational: callers must not use it as an authorization, worker-selection, or correctness
  * signal. Its absence is expected for official Jev responses. The observed local service returns `workflow: null`; a
  * non-null workflow is modeled as text until Laya documents a stable workflow schema.
  */
final case class SystemOneRouting(
    model: String,
    repo: String,
    reason: String,
    detection: SystemOneLanguageDetection,
    // workflow: Option[String] = None
) derives JsonCodec

/** Laya's language/script detection diagnostics nested in [[SystemOneRouting]]. */
final case class SystemOneLanguageDetection(
    script: String,
    @jsonField("script_profile") scriptProfile: Map[String, Double],
    language: String,
    @jsonField("is_english") isEnglish: Boolean,
    @jsonField("language_undecided") languageUndecided: Boolean,
    @jsonField("diacritic_rate") diacriticRate: Double,
    @jsonField("non_latin_fraction") nonLatinFraction: Double,
    @jsonField("mixed_segment") mixedSegment: Option[String] = None
) derives JsonCodec
