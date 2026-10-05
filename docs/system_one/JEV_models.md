# Typed Jev model example

A fixed question schema is an ordinary case class. Its field names become System One question IDs; use `@jsonField` when the wire ID differs from the Scala name.

```scala
import fmgp.yallmo.model.som.*
import fmgp.yallmo.model.som.SO.*
import zio.json.{JsonCodec, jsonField}

final case class ReviewState(
  task: String,
  changedFiles: Int
) derives JsonCodec

final case class ReviewQuestions(
  worker: SystemOneQuestion.Choice,
  @jsonField("needs_review") needsReview: SystemOneQuestion.Noul,
  complexity: SystemOneQuestion.Score
) derives JsonCodec, SOSchema

val questions = ReviewQuestions(
  worker = choice(
    "Which worker should handle `task`?",
    "review" -> "Audit a change",
    "coding" -> "Implement code"
  ),
  needsReview = noul("Does `task` require review?"),
  complexity = score("How difficult is `task`?", "simple", "complex")
)

val request = SORequest(
  state = ReviewState("Review this change", changedFiles = 7),
  model = "jev-latest",
  questions = questions
)
```

The request has this essential JSON shape:

```json
{
  "state": {"task": "Review this change", "changedFiles": 7},
  "model": "jev-latest",
  "questions": {
    "worker": {"type": "choice", "...": "..."},
    "needs_review": {"type": "noul", "...": "..."},
    "complexity": {"type": "score", "...": "..."}
  }
}
```

After a response is decoded and validated, field access is statically typed:

```scala
val answers = request
  .answersFromJson(responseJson)
  .fold(error => throw new IllegalArgumentException(error), identity)

val worker: SystemOneAnswer.Choice = answers.worker
val needsReview: SystemOneAnswer.Noul = answers.needsReview
val complexity: SystemOneAnswer.Score = answers.complexity
```

`SOSchema` derives these guarantees:

- `Questions` must be a case class containing only Choice, Noul, and Score fields.
- response answer IDs honor `@jsonField` names;
- every declared answer is present and has the matching primitive;
- `schema.fields` exposes `SOField(id, SOPrimitive)` metadata in declaration order.

This is a typed façade over the same JSON protocol. It does not make Jev an LLM provider or an agent.

## Future work

### Validation contract

`SOSchema` currently validates that the question schema is a case class containing only Choice, Noul, and Score fields; honors `@jsonField` wire IDs; and requires each declared response answer to be present with the matching primitive.

It does **not** yet validate all Jev semantics. In particular, future validation should check that Choice values and probability keys match submitted criteria, probabilities and scores are finite and within valid ranges, Score values fit the submitted rubric, and unexpected answer IDs follow an explicit policy.

Obtain answers through `request.answers(...)`, `request.answersFromJson(...)`, or `SOSchema.decodeAnswer(...)`. `SOAnswer.unsafeFrom(...)` exists for generated macro code and deliberately bypasses validation.
