# Opt-in managed-history decisions (step 6)

An agent **program**, not System One or a server default, chooses whether there is a
candidate. `SystemOneHistoryDecision.prepare` asks one typed closed Keep/Rebase
question using `SystemOneDecisionAgent`/`SOMProvider`. Only caller-curated plain-text
evidence is sent; message IDs, messages, encrypted reasoning, attachments/blob bytes
and provider credentials are not extracted or sent by the helper. The caller must
bound and sanitize its evidence. Neither the question nor the answer is appended to
the conversation. No tool runs during evaluation/rebase, and the decision grants no
permissions.

## Usage inside an agent program

For a program whose temporary research section follows the first retained request,
with a final summary immediately after its last temporary note:

```scala
// delegate is the existing conversational AgentInstance[SOMProvider] (e.g. a bound native adapter).
val advisor = SystemOneHistoryDecision("jev-latest", minConfidence = 0.9)
val program = new AgentInstance[SOMProvider] {
  val id = delegate.id
  override val capabilities = delegate.capabilities
  override def configuredContext(context: StaticContext[SOMProvider]) =
    delegate.configuredContext(context)
  def next(task: AgentTask, context: StaticContext[SOMProvider], current: RunContext) =
    delegate.next(task, context, current) // Invocation stays a temporary, non-managed turn.

  override def nextManaged(task: AgentTask, context: StaticContext[SOMProvider],
      current: RunContext, history: ManagedHistory) =
    for {
      fresh <- advisor.prepare(history) { active =>
        val noteIndex = active.indexWhere(_.payload match {
          case MessagePayload.Context("temporary-research", _) => true
          case _ => false
        })
        val hasSummary = active.lift(noteIndex + 1).exists(_.payload match {
          case MessagePayload.Context("research-summary", _) => true
          case _ => false
        })
        if noteIndex > 0 && hasSummary then
          Some((active(noteIndex).id, active.last.id, Some(active.head.id),
            "Temporary research is superseded by a verified summary. Retain the summary and current task."))
        else None
      }
      turn <- delegate.nextManaged(task, context, fresh, history)
    } yield turn
}
// Run with the normal AgentRunner.execution(program, context), through Orchestrator.submit,
// explicitly providing the configured SOMProvider. No provider is called by default.
```

This is an application-owned selection rule, not a generic detector of disposable
context. The tuple is `(exclusiveBase, inclusiveHead, onto, boundedEvidence)`.
All IDs must be in the inspected active snapshot and head must be its current head.
Missing/invalid candidate IDs or `None` skip evaluation and KEEP. The existing managed
rebase validates ancestry, complete independent tool exchanges, authors and objects;
changed chains remove opaque reasoning without inspecting it. It can still fail:
real range/object errors propagate, and the delegate is not called on failure.

## Small host-owned policy

- Require choice `rebase`, exactly the `keep`/`rebase` probability keys, finite
  probabilities and official confidence in `[0,1]`, and probabilities summing to
  one within `1e-6`. Rebase must be the unique winner.
- Both rebase probability and official confidence must meet the caller's threshold
  (default `0.9`, configurable finite value in `(0.5,1]`). Optional Laya confidence
  extensions are not authority. Other answers, uncertainty, schema/decode failures
  and typed provider failures conservatively KEEP, without logging raw errors or
  evidence. There are no retries; interruption/defects are not fallback answers.
- Capture the active `Option[MessageId]` head and recheck it after advisory latency.
  A different local head (including an ordinary append) stops before delegation.
  Public managed rebase checks the source head again atomically under the existing
  submission-local gate; there is no extra counter or private advisory rebase API.
  Exact restoration A -> B -> A restores identical immutable ancestry and is accepted,
  even between advisory recheck and atomic application. This is not a session lock.
  External session-pointer changes do not invalidate immutable source/rebase:
  independent publication conflicts still log ERROR, preserving the competing head.
- Return a fresh canonical `RunContext` and refresh the runner's program fence via
  `history.messages`. Pass it **and the same history capability** to the delegate's
  `nextManaged`; do not reuse the incoming snapshot or append advisory messages.
  Ordinary author/capability/action checks, async checkpointing and submission
  lifetime remain unchanged.

`SystemOneHistoryDecisionSuite` runs on JVM/JS with a recording System One transport
that decodes the real request envelope. `ManagedHistorySubmitSuite` demonstrates
research-note cleanup, native provider continuation, KEEP/failures, cancellation,
different-head rejection, positive exact restoration and competing publication on memory/filesystem (including reopen).
The existing failed-tool retry demonstration remains independent. These are fake
provider acceptance checks, **not live-provider acceptance**. Step 7 automated
integration is implemented; independent review and live/manual acceptance remain
separate gates.
## Typed question encoding

`SORequest.toJson`, `SOSchema.encodeRequest` and typed `SOMProvider.evaluate` share
one generic wire encoder. The helper's private question schema uses normal
`derives JsonCodec, SOSchema`; no local discriminator workaround is needed.
Encoding now requires `SOSchema[Q]` as well as `JsonEncoder[Q]`: direct typed
encoding of arbitrary question objects without a schema is no longer supported.
Use map-backed `SystemOneRequest` for dynamic questions; its codec is unchanged.

The caller's state/question encoders retain aliases and payloads. The typed boundary
adds only missing question `type` hints for Choice/Score/Noul. A custom question
encoder must emit exactly the schema's IDs, each with an object payload and an absent
or matching hint. Duplicate IDs/fields, missing/extra questions, conflicting hints
and non-object payloads fail explicitly (direct encoding throws
`IllegalArgumentException`; typed provider evaluation returns `ProtocolFailure`
before transport). Criteria, instructions, optional Noul criteria and custom payload
members are not reconstructed. Request decoding still uses the supplied state/Q
decoders; this is not a new general strict protocol validator.
