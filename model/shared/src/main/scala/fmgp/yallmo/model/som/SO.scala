package fmgp.yallmo.model.som

/** Concise, opt-in DSL constructors for [[SystemOneRequest]] and [[SystemOneQuestion]].
  *
  * `apply` is the flat form, so `SO(state, model, questions*)` uses Scala's apply syntax. `request` is the equivalent
  * curried form, useful when named request fields should be visually separate from the question list.
  *
  * [[request]] constructs the map-backed wire model and is appropriate when question IDs are assembled at runtime. The
  * [[choice]], [[score]], and [[noul]] helpers construct ordinary [[SystemOneQuestion]] values and can also be used in
  * a fixed [[SORequest]] case-class schema with `derives SOSchema`.
  */
object SO {

  // /** Builds a System One request using a single parameter list. */
  // def apply[State](
  //     state: State,
  //     model: String,
  //     questions: (String, SystemOneQuestion)*
  // ): SystemOneRequest[State] =
  //   SystemOneRequest(state, model, Map.from(questions))

  /** Builds a System One request with its question list in a second parameter list. */
  def request[State](
      state: State,
      model: String
  )(
      questions: (String, SystemOneQuestion)*
  ): SystemOneRequest[State] =
    SystemOneRequest(state, model, Map.from(questions)) // apply(state, model, questions*)

  /** Builds a question that selects one of the supplied criterion IDs. */
  def choice(
      instructions: String,
      criteria: (String, String)*
  ): SystemOneQuestion.Choice =
    SystemOneQuestion.Choice(instructions, Map.from(criteria))

  /** Builds a question that scores against the supplied ordered levels. */
  def score(instructions: String, levels: String*): SystemOneQuestion.Score =
    SystemOneQuestion.Score(instructions, levels)

  /** Builds a yes/no question with no outcome descriptions. */
  def noul(instructions: String): SystemOneQuestion.Noul =
    SystemOneQuestion.Noul(instructions)

  /** Builds a yes/no question with descriptions for its true and false outcomes. */
  def noul(
      instructions: String,
      whenTrue: String,
      whenFalse: String
  ): SystemOneQuestion.Noul =
    SystemOneQuestion.Noul(instructions, Some(SystemOneNoulCriteria(whenTrue, whenFalse)))
}
