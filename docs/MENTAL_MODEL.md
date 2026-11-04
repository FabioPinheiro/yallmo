# Yallmo's mental model

Start here when deciding where code belongs:

```text
ApplicationRuntime → wires the application together
RuntimeInstances   → constructs and caches agents/providers
Agent              → prepares a model turn
AgentRunner        → controls the loop and executes tools
Tools              → perform specific operations
```

This is a responsibility map, not an inheritance hierarchy. In particular, an `Agent` handles **one turn**, not the
whole conversation. Most agents use a model; provider-free agents can complete a turn without one.

## Who does what?

| Component | Responsibility | Not its responsibility |
| --- | --- | --- |
| `ApplicationRuntime` | Connect configuration, selected agents, execution settings, tools and session services. | Perform inference or implement the agent loop. |
| `RuntimeInstances` | Lazily construct agents/providers, bind dependencies and reuse successful instances by ID. | Store conversations or execute tools. |
| `Agent` | Handle one turn: prepare/delegate a model request or perform provider-free work, then return a response. | Own the runner's multi-turn tool loop. |
| `AgentRunner` | Drive an agent program, enforce budgets and available capabilities, validate tool arguments and execute requested actions. | Implement provider HTTP protocols or persist session history. |
| Tools | Implement specific operations using their supplied dependencies, such as saving an application record. | Decide the overall conversation flow. |

Two supporting roles complete the picture:

- **Provider:** performs model inference through a particular backend/account. A provider connection can be shared by
  multiple agents; it does not own the application tool loop.
- **Orchestrator:** owns session/history operations and coordinates persist–run–persist around an execution.

## Setup versus execution

```text
SETUP
Configuration
    │
    ▼
ApplicationRuntime ──uses──► RuntimeInstances
    │                           │
    │                           └── initialized agents/providers
    ▼
Assembly: session services + tools + context
    │
    └── selected agent binding → submission service

EXECUTION
User message
    │
    ▼
Orchestrator records the message
    │
    ▼
AgentRunner
    ├── asks the agent program for its next decision
    │      └── native-model path: Agent.turn → provider → response
    ├── final answer? ───────────────────────────────────┐
    └── tool call? → validate → execute tool → next turn │
                                                       ▼
                                      Orchestrator records the result
```

The model may **propose** a tool call. Trusted application code decides whether it is available and executes it.
Agent-owned tools are bound to the selected agent; exposing a tool implementation does not automatically grant
permission to use it.

## Managed history in ordinary submissions

`Orchestrator.submit` durably accepts the prompt once, then supplies `ManagedHistory` to the runner. An agent program's
`nextManaged` entry point can read identified active messages, including generated calls/results before disk saves finish.
Mutation is runner-controlled: `ManagedHistory` is a trait with identified reads and an orchestration-scoped append of
**new payloads only**. Immutable Message IDs, timestamps and parents are allocated once and queued interruption-safely.
There is no full-transcript save/checkpoint adapter and no storage handle on this capability.

One asynchronous writer stores the exact messages independently in parent order, retrying identical objects after failures.
Session-head publication is separate, using the existing best-effort CAS. A concurrent append keeps its pointer; all generated
objects can still be inspected through `Orchestrator.listMessages` as their original branch. A pointer conflict is not an
unsaved-object warning and never regenerates identities. Pending writes drain on success, failure, timeout and cancellation;
a stuck backend can still delay draining indefinitely, and permanent object failures remain best-effort/logged.

Each ordinary turn derives a fresh `RunContext` from managed history for extensions and provider rendering. Extensions
must preserve that snapshot's prefix; only their new suffix is appended before inference. Assistant/reasoning messages
and calls append before tools execute, and each result appends immediately after its action completes. Neither loop
recursion nor tool execution carries an authoritative `RunContext`. The compatibility `AgentTurnResult` snapshot is
output only: submission responses use the accepted prompt plus an append-only execution audit, never a replaced active-prefix length
or a returned transcript. Rebase copies are context, not new execution.

Request-only filters (including continuation reset) forward `nextManaged` with the original history capability and filter
only the temporary payload snapshot, never the canonical transcript. Continuation resets exclude captured opaque
continuation values, not positions in rewritten history: fresh post-rebase reasoning remains available to later turns.
`ManagedHistory.initialMessages` is the immutable per-submission acceptance snapshot (including its prompt); reading
it does not refresh the program's captured work head. The normal configured server captures exclusions once on reset,
agent switch or first selection and reuses them across later submissions. Legacy in-process `continuationStart: Int`
is interpreted against each submission's initial snapshot; those callers must recalculate after a persisted rewrite.
No mutable reset state is stored on reusable execution/agent wrappers. Native/folder bindings retain their capability
validation. Invocation is one temporary-input turn, not a managed multi-turn transcript; the deprecated provider turn
path remains a direct request/response operation without managed history. Ordinary execution gains no session-wide
serialization or new pointer policy.

## Basic immutable message rebase

`Orchestrator.rebase(base, head, onto)` selects messages after the **exclusive** base through the **inclusive** head,
following parent IDs rather than timestamps or session membership. It returns chronological persisted copies attached
to `onto`, or to a new root when `onto` is `None`. Authors, timestamps and payloads are unchanged; only parents and
content-derived IDs change. Original messages remain inspectable. Replaying onto `Some(base)` preserves the original
IDs with idempotent writes; `base == head` returns an empty chunk after validating references. A destination inside
the source range is valid: retained originals are not a cycle in the new immutable chain.

The implementation snapshots persisted objects once, validates all references, complete source/destination ancestry,
hashes and registered authors before any candidate write, then puts copies parent-first. Missing history, invalid
hashes and an unreachable base fail with typed errors, including for empty/no-op requests. Isolated objects need not
belong to a session. This simple implementation scans the object inventory; it introduces no storage format/index redesign.
Success means all returned objects are saved, including across filesystem reopen. Failure or interruption may leave a
saved prefix: this is **not** a batch rollback transaction. No session is created, no session head or active
`ManagedHistory` is changed, and no active writer is drained. Not-yet-persisted active messages are unavailable to this API.

Rebase copies data only: it runs no tools or inference, does not copy blob bytes and does not assert provider continuation
replay safety. Editable rebase operations and model-facing editing remain unapproved later steps.

## Managed active rebase (step 4)

Programs receive `ManagedHistory.rebase(base, head, onto)`, without Storage access or session-reference wrappers.
The source head must equal the current **local** active head; a newer local append makes that source stale. The
operation activates destination ancestry followed by the rewritten suffix. Empty ranges activate the destination,
including an empty root; exact no-ops preserve identities. External session changes do not invalidate this operation.

A per-submission mutation gate (not a session lock) orders appends, rebase and close. Ordinary appends remain
asynchronous. An explicit rebase drains preceding writer commands and awaits required object persistence before
copying; object/validation failure or cancellation before activation leaves canonical context unchanged. A never-replaced
object journal and cursor preserve original execution objects independently of active context length. The short
activation/enqueue boundary is interruption-safe; the scoped writer drains after success, failure or cancellation.
Captured capabilities expire at close; reads remain available but late mutations fail.

Session publication is separate, message-first and best-effort. The private storage head CAS compares only the last
owned head, preserves latest participants, and retains original session-only memory objects before replacing refs.
Failures log ERROR with session/old/new IDs, never payloads; exact already-published targets may be acknowledged, but
competing heads are never reloaded and overwritten. Continued branch messages still persist when publication fails.
Original objects and parents remain inspectable through the inventory (including filesystem reload), without automatic
fork names. Logs record base/head/onto, old/new active heads and ordered original/copy ID pairs. This is **log-only
operation provenance**, not a durable queryable operation journal or crash-recovery guarantee.

Runner extension, model and action work captures the active `Option[MessageId]` head. Under the submission-local gate,
appends compare that parent and return their own resulting head atomically. The runner advances only through its own
accepted writes: extension suffix to model turn, assistant/calls to action batch, and each result to the next action.
It never adopts an unrelated latest head for a queued decision. Ordinary concurrent appends or rewrites to a different
head invalidate in-flight work; exact restoration A -> B -> A is the same immutable context and is accepted, not stale.
Stale completed output is saved under its original parent and retained in execution audit, never silently attached to
new context; continuation fails without rerunning side effects. Empty batches still check the captured head, including
`None` for empty history. Session pointer-only changes do not affect this local identity. A program that awaits a rebase inside `nextManaged` must explicitly read `history.messages`
and rebuild any inference request before returning output. This trusted-program contract is **not proof** that an LLM
consumed the refreshed snapshot; unrelated extension/tool work cannot refresh its own fence through that read. Subsequent
runner turns obtain fresh snapshots. A rewrite does not change original message authors or count copies as execution.

Managed rebase accepts complete independent tool exchanges: destination ancestry and the selected source range
must each contain unique call IDs with one matching-name result after each call. The final chain is validated too;
results cannot be reassociated with a different original call. Calls/results cannot pair across a selection cut.
Batched calls and out-of-call-order results are supported; arguments, call IDs, outputs, authors and timestamps stay
unchanged. Rebase never executes tools or reverses effects. Exact unchanged no-ops preserve every message and payload.

For a changed active chain, all opaque `Reasoning` entries are conservatively removed from both destination and
selected source **before** deriving parents/hashes or writing copies. Ciphertext is never inspected or rewritten;
original stored messages and actual execution audit remain unchanged. The managed return value is the retained
source suffix, possibly empty; `history.messages` and the session publication target contain the full sanitized chain.
Identifier-only provenance maps each retained original to its actual copy and lists removed reasoning IDs, never content.
Object-save failure leaves active history/pointer unchanged, though a partial immutable prefix may remain stored.
Standalone `Orchestrator.rebase` remains context-neutral and payload-preserving; it makes no provider replay promise.

The deterministic retry demonstration uses a recording fake provider through the native request mapper: a real fake-tool
failure result, explicit retry success, managed rebase, explicit refreshed request and continued tool/inference execution
in the same submission. It proves refreshed input and unchanged execution counts, not live-provider acceptance.
General pick/edit/drop/squash plans, automatic retry classification, default model-decision policy and new session/fork
policies remain deferred. Step 6 adds only the explicitly opted-in program composition described below.
Inventory-wide scans, unbounded writer queue, stuck storage delaying drain, best-effort ordinary saves and absence
of crash transactions/exactly-once guarantees remain limitations.

## Opt-in System One history preparation (step 6)

An agent program can call `SystemOneHistoryDecision.prepare(history)(select)` before delegating its managed turn.
Trusted deterministic code selects existing active base/head/onto IDs and explicitly bounded, sanitized text evidence;
a closed typed System One question may advise only Keep/Rebase, never arbitrary IDs or authorization. Invalid/uncertain
answers or typed provider failures KEEP; cancellation and real rebase failures propagate. The captured source
head is checked after model latency; public managed rebase checks that head again atomically under the existing gate.
Exact restoration of the same head/ancestry is accepted, including between recheck and application. External
session-pointer changes remain independent. The helper explicitly reads fresh canonical history and returns a temporary
RunContext for the delegate's `nextManaged` with the same capability; advisory prompts/answers never become messages.
Nothing registers this workflow automatically. See the [usage snippet and host policy](system_one/MANAGED_HISTORY_DECISIONS.md).
The research-note example is not a retry workflow; recording fake providers demonstrate the request actually refreshed,
not live service acceptance. Step 7 integration is approved; independent review and
[manual/live acceptance](orchestration/MANAGED_HISTORY_ACCEPTANCE.md) remain separate pending gates.

## Inspecting originals and active context

- `ManagedHistory.messages` is the current local branch during a submission, not a durability barrier.
- `Orchestrator.loadSession(id)` follows the **published session head**. After successful publication/reopen it shows
  the rewritten chain. After a CAS conflict it still shows the competing branch, not necessarily the successful
  local rewrite. `SessionTurn.session` is only that submission's last acknowledged publication, potentially older
  than the current pointer; `newMessages` is execution audit, not the active transcript.
- `Orchestrator.listMessages` is the global immutable object inventory, not a per-session transcript. Follow IDs and
  parent IDs to inspect original, copied and stale-output branches after writes drain, including filesystem reopen.
  The existing web **All messages** and **Conversation graph** views use this inventory. Expand a graph node to inspect
  the ID, parent, author and payload; shared ancestors appear once. The graph stops at depth 32, so use All messages
  for deeper nodes. The Sessions view follows published pointers and does not select unpublished local heads.
- Graph edges prove ancestry, not rewrite origin. Copy/original and removed-reasoning mappings are identifier-only
  logs; there is no durable operation/provenance journal. Keep the logged IDs if that relationship matters.
- Pointer-conflict branches remain inspectable but are not automatically named, resumed or attached to sessions.
  `fork` forks the current published session, not an arbitrary inventory head; standalone `rebase` only copies objects.
  No public arbitrary-head publication/recovery UI or new session policy is provided. Do not treat a pointer conflict
  as automatic branch recovery or promise that later submissions will resume that local rewrite.

See [integration evidence and pending acceptance](orchestration/MANAGED_HISTORY_ACCEPTANCE.md).

## Ownership and lifetime

In a configured standalone server:

```text
ServerApplication.Configured
    ├── ApplicationRuntime
    │      └── RuntimeInstances
    │             ├── cached agents
    │             └── cached providers
    └── Assembly
           ├── orchestrator / session storage
           └── shared tools and context
```

Connections and sessions reuse this application composition. Selecting another agent does not create another runtime.
ZIO scopes manage resource lifetime; the tree above describes ordinary object references, not a language-enforced
ownership system. Configuration is a snapshot: editing the file does not mutate existing instances automatically.

## Keep it simple

- Prefer clear responsibilities over increasingly generic infrastructure.
- Add an abstraction to solve a concrete problem, not merely to remove a small `match` or hide a dependency.
- Keep permission checks, workspace scoping and persistence boundaries explicit.
- Treat this map as the intended separation, not a claim that every current implementation is perfectly decoupled.

## Code and related documentation

- [ApplicationRuntime](../server/src/main/scala/fmgp/yallmo/server/application/ApplicationRuntime.scala)
- [RuntimeInstances](../core/jvm/src/main/scala/fmgp/yallmo/orchestration/RuntimeInstances.scala)
- [Agent](../model/shared/src/main/scala/fmgp/yallmo/model/Agent.scala)
- [AgentRunner](../core/shared/src/main/scala/fmgp/yallmo/orchestration/AgentRunner.scala)
- [Tool interfaces](../core/shared/src/main/scala/fmgp/yallmo/orchestration/Capabilities.scala)
- [Agent and provider configuration](CONFIG_AGENT_PROVIDER.md)
- [Capabilities architecture](Capabilities_Architecture.md)
