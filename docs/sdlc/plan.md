# Plan tool — design definition

**Status: task/intent models, agent tools, shared JSONL storage, read-only HTTP and the initial web inspector are implemented.**

This document captures the intentions, current design decisions, implemented data
models, and remaining questions. Task tools and storage are implemented through
rooted filesystem capabilities; see [README.md](README.md) for usage and guarantees.

## 1. Intent

Build a lightweight SDLC planning tool primarily operated by LLM agents, with a
visualization portal where the user can inspect the work and guide its evolution.

The tool is shared working memory between the user and agents:

- Preserve the intended outcomes while the approach changes.
- Track work at any scale, from a large initiative to renaming one method.
- Support changing scope, ordering, dependencies, and decomposition.
- Keep unresolved decisions, assumptions, and leftover work visible.
- Let conversations refer unambiguously to specific elements.
- Keep active context small without losing traceability to older work.
- Make JSON simple enough for an LLM to generate and understand in one explanation.

Agents usually maintain the records according to user guidance. Recommendations
are not user decisions, and confidence is not authorization to proceed.

## 2. The mental model

> Intents describe desired outcomes. Tasks describe work or decisions. Tasks can
> contain tasks, depend on tasks, contribute to intents, and leave tasks behind.
> Assumptions describe beliefs being treated as true. A plan is a view of this
> information, not another entity.

Use **Intent** for the concept; “goal” means the same thing here. Its stored Scala
model is `PlanIntent`; the stored task model is `PlanTask`. There is no separate
Goal class and no separate Plan class.

A **project** groups all its intents and tasks. The initial implementation uses
one shared server SDLC directory with separate task and intent journals; records
do not repeat a project ID. Multi-project selection remains future work.

## 3. Current design decisions

### 3.1 Tasks at every scale

- Use the same Task structure for broad work and small concrete actions.
- Represent decomposition with a list of subtask IDs, not nested task objects.
- Subtask order is the current preferred display/execution order, not a hard dependency.
- Use explicit dependencies when work actually requires other work.
- A task may exist without an intent.
- Do not duplicate a task merely because it moves or becomes leftover work.

### 3.2 Intents and contributions

- An intent describes the outcome, including how success will be recognized in its text.
- A task can contribute to multiple intents.
- Contribution does not mean that the task is required.
- An intent can depend on tasks that are required before declaring it achieved.
- Work optional for an intent can be skipped without automatically invalidating that intent.
- Optionality is relative to an intent, not necessarily a global property of the task.
- Completing required tasks alone is not proof that the intended outcome was achieved.

**Omit contribution percentages for the initial definition.** They were considered,
but estimated impact, task progress, and confidence are different concepts. A
percentage needs an agreed interpretation before it becomes a useful field.

### 3.3 Relationships

Relationships are simple lists of stable IDs on the relevant record. There is no
separate Link class or arbitrary graph schema.

| Field | On | References | Meaning |
| --- | --- | --- | --- |
| `subtasks` | Task | Tasks | Work contained in this task, in current order |
| `dependsOn` | Task | Tasks | Prerequisite work |
| `dependsOn` | Intent | Tasks | Required work before declaring the outcome achieved |
| `leftoverOf` | Task | Tasks | Work from which this remaining work originated |
| `contributesTo` | Task | Intents | Outcomes this work is expected to help |

The initial named cross-task relationships are `dependsOn` and `leftoverOf`.
Containment and intent contribution have their dedicated lists.

Containment, display order, contribution, and leftover origin do not imply a
prerequisite dependency. Intent dependencies describe achievement requirements;
they do not prevent starting work toward the intent.

### 3.4 Stable references

- IDs are permanent, short, and unique within a project across tasks and intents.
- Titles, ordering, and hierarchy must not determine an ID.
- Moving, renaming, archiving, or updating an object must not change its ID.
- The agent, conversation, tools, and portal use the same references.
- The API allocates IDs; the LLM need not invent them.

Example conversation:

> “Proceed with T-12, but leave the decision in T-19 for me.”
>
> “The assumption in T-24 is wrong; external callers still use that method.”

Prefixes such as `T-12` and `I-3` are a readable convention, not a finalized ID allocator.

### 3.5 Minimal task lifecycle

Use four persisted states:

- `open`: unresolved work not currently being executed.
- `doing`: work in progress.
- `done`: the task's completion conditions have been satisfied.
- `dropped`: intentionally no longer pursued.

Keep completion conditions in the task text initially. Explain a significant
change, especially dropping work, in the updated text or associated conversation.

Do not introduce separate blocked, deferred, or awaiting-user states initially.
Dependencies and the task text provide that context. Structured decision handling
can be added later if reliable automation or portal filtering requires it.

A parent is not automatically done when all subtasks are done. Dropped work does
not automatically satisfy a dependency. No detailed transition enforcement is
specified yet.

### 3.6 Decisions and recommendations

A decision can be an ordinary task. Its text can contain:

- The question and alternatives.
- The agent's recommendation and rationale.
- Uncertainty or assumptions behind that recommendation.
- Whether user input is required.
- The user's response and any qualifications.

For example: “Choose session storage. Recommend PostgreSQL to avoid another
service. Awaiting user approval.”

Keep these concepts distinct even though the first version uses text:

- Agent confidence in a recommendation.
- User agreement, disagreement, or uncertainty.
- Authorization to proceed.

Do not infer approval from a recommendation, silence, or a confidence statement.
Structured options, confidence scores, approval fields, and decision entities are
not part of the initial schema. Text is not a machine-enforced approval mechanism.

### 3.7 Assumptions

Tasks may have an optional list of assumption strings.

An assumption is a belief currently being treated as true, not an established
fact. When new evidence contradicts it, reassess the task and update its text,
assumptions, or related work; do not automatically cancel it.

For simplicity, omit assumption IDs, statuses, confidence scores, evidence fields,
and validation references. A conversation can reference the task ID and quote the
assumption. Assumptions on intents are a possible later extension, not required now.

### 3.8 Leftovers

- Existing unfinished work keeps its existing task ID.
- Newly discovered remaining work can be a new task with `leftoverOf` references.
- Leftover origin does not imply a dependency.
- Remaining work must stay visible after its origin is closed or archived.
- Do not label required unfinished scope as optional leftover merely to declare a task done.

Before closing broad work, account for unresolved work: retain it, move it into
other work, defer it explicitly, or drop it with a reason. Avoid an invisible,
unbounded backlog.

### 3.9 Archive and maintainability

Archiving changes everyday visibility, not identity, completion, or meaning.

- Keep archived records searchable and reachable through references.
- Do not delete their history or break links.
- Do not implicitly archive descendants.
- Do not hide unresolved work merely because its parent is archived.
- Keep the current description concise; older snapshots preserve previous versions.

The draft represents this with an optional/defaulted `archived` boolean. Review
schedules, retention policies, and history compaction are not defined yet.

## 4. Implemented Scala models

The model layer lives in the existing cross-platform `core` module:

- `core/shared/src/main/scala/fmgp/yallmo/tool/sdlc/PlanTask.scala`
- `core/shared/src/main/scala/fmgp/yallmo/tool/sdlc/PlanIntent.scala`
- `core/shared/src/main/scala/fmgp/yallmo/tool/sdlc/TaskState.scala`
- `core/shared/src/main/scala/fmgp/yallmo/tool/sdlc/llm/PlanTaskSimplifiedModel.scala`
- `core/shared/src/main/scala/fmgp/yallmo/tool/sdlc/llm/PlanIntentSimplifiedModel.scala`

The root package `fmgp.yallmo.tool.sdlc` contains complete stored snapshots.
`PlanTask` and `PlanIntent` include required `createdAt` and `updatedAt`
timestamps (`java.time.Instant`), maintained by the runtime rather than the LLM.

The `.llm` package contains simplified models with every planning field,
including IDs, relationships, and archive visibility, but no storage timestamps.
These are explicitly declared case classes, not generated projections. Each model
uses `derives JsonCodec` from `zio.json`; JSON names match Scala field names.

IDs are strings. Lists default to empty, archive visibility defaults to false,
and tasks default to `TaskState.Open`. States encode as `open`, `doing`,
`done`, and `dropped`.

Stored models expose `toSimplifiedModel`. Simplified models expose
`toPlanTask(createdAt, updatedAt)` or `toPlanIntent(createdAt, updatedAt)`.
These are pure conversions, not persistence operations. When applying an update,
the runtime preserves the existing ID and creation timestamp and
supply the new update timestamp.

Task and intent tools, storage, ID allocation, reference checks and task-graph
validation are implemented. Schemas are explicit, not derived. Custom macro
generation remains unimplemented.

## 5. Agent-facing JSON

Agents can discover work with bounded `sdlc_list` summaries, text search,
archive filtering and `afterId` continuation. Full reads are still required before
editing; summaries do not contain complete replacement content.

The agreed task API direction is:

```text
Set(tasksJson)
ReadTask(setOfTaskIds)
```

These are registered as `sdlc_set` (argument `tasksJson`, a JSON array) and
`sdlc_read_task` (argument `taskIds`, an array of unique known IDs). Both return
`PlanTaskSimplifiedModel` arrays, not separate creation/update command models.
Task JSON includes the stable ID and planning content, for example:

```json
{
  "id": "T-1",
  "text": "Rename the authentication method after checking callers",
  "assumptions": ["No external clients use the current name"]
}
```

Each supplied task is a complete replacement of its planning content, not a patch.
Omitted defaulted fields become empty lists, `open`, or `false`; they do not
mean “preserve the old field.” Tasks not supplied are not implicitly deleted.
The LLM does not supply `createdAt` or `updatedAt`.

For creation, supply an empty `id`; the runtime allocates a stable `T-n` ID and
returns it. Nonempty IDs must already exist. The matching intent tools are
`sdlc_set_intents(intentsJson)` and `sdlc_read_intent(intentIds)`, returning
simplified intent snapshots and allocating `I-n` IDs for empty creation IDs.

## 6. JSONL persistence

The implemented task store uses the existing `FsRead` and `FsWrite` capabilities.
It captures the shared server `--storage` root and writes `sdlc/tasks.jsonl`
and `sdlc/intents.jsonl`.
With `--storage ./server-storage`, the SDLC directory is `./server-storage/sdlc`,
shared across authorized agents and sessions regardless of their workspace or
per-agent filesystem roots.
Directories are created only on the first nonempty write.

Each physical line contains a JSON array of the complete changed `PlanTask`
snapshots from one Set operation, including runtime-owned creation/update
timestamps. Replay applies batches in order; the latest snapshot for an ID wins.
Grouping snapshots in one line prevents accepting only part of a batch during
replay. Intents use the same batch format in their separate intent journal.

This replaces the earlier proposed mixed task/intent, one-record-per-line format.
There is no mixed-record discriminator or separate event model. `SdlcService`
shares both storage owners between agent tools and HTTP routes.

All five tools, authorized agents and HTTP inspection share one `SdlcService`
that serializes read, reference validation and append. Writes use a scoped append writer and `sync`; corruption, trailing
content, incomplete final records and configured limits fail closed.

Updates are full planning replacements protected by `expectedRevision`. Stored
snapshots carry runtime revisions; reads expose the version to echo on updates.
Stale versions reject the entire batch. Legacy histories without revisions infer
them during replay. Independent owners/processes must not write the same journal concurrently. Filesystem failures are not transactions
and can leave an incomplete record or an uncertain successful commit. Creating
with an empty ID is not retry-idempotent.

History records what changed and when, but not the actor, reason or conversation.
Explicit checkpointing now preserves history copies while compacting the current
journal, with a failure marker guarding uncertain replacement. Active-work limits
exclude closed/archived tasks and archived intents; retained data remains bounded.
Audit attribution, multi-project selection and cross-process coordination remain
future work. See README for maintenance and recovery guarantees. See [README.md](README.md) for
the precise limits and recovery caveats.

## 7. Portal intentions

The portal is an inspection and guidance surface over the same records used by the
agents, not an independent planning model.

Useful views:

- **Work:** task hierarchy, current state, dependencies, and related intents.
- **Needs input:** decisions or questions requiring user guidance.
- **Changes:** evolving descriptions, decisions, and leftover work.
- **Archive/search:** older work, still reachable by stable ID.

The initial `#/sdlc` page displays current tasks and intents, including archived
records. Each card shows its ID and the first line of text as its title; a button
expands/collapses the full JSON. It loads complete stored objects via read-only
HTTP `GET /api/sdlc/tasks` and `GET /api/sdlc/intents`, not WebSocket/RPC or
simplified LLM models. Server startup creates the shared service used by both
HTTP and agent tools. This is an inspection view, not yet a history browser or
editable planning UI.

Reliable automatic “needs input” filtering and richer audit presentation may
require structured metadata later; free text does not already solve these.

## 8. Deferred work and open questions

Before extending task execution and storage, settle what is needed for the next step:

1. **Storage evolution:** project selection, retained-data/disk retention policies,
   and whether stronger cross-process transactions are needed. Explicit
   checkpointing is implemented; automatic repair/rotation is not.
2. **Audit attribution:** how to preserve who changed what, why, and the originating
   conversation without burdening LLM creation input.
3. **User decisions:** whether an explicit approval/input marker is needed for the
   first portal. Free-text recommendations must never be treated as approval.
4. **Intent lifecycle:** whether explicit achieved/abandoned states are needed beyond
   outcome text, task dependencies, and archive visibility.
5. **Validation evolution:** task references, intent contributions, duplicate IDs,
   self-links and task graph cycles are checked. Future approval/lifecycle rules
   must not be inferred from free text.
6. **Decomposition:** multiple parents are currently allowed; decide whether later
   automation needs stronger containment rules.
7. **Maintenance:** how deferred work resurfaces and how archived history is retained
   or compacted without breaking traceability.

Do not add separate Plan, Decision, Assumption, Link, or Change entities, arbitrary
relationship types, contribution percentages, confidence scores, deeply structured
completion criteria, or a full event-sourcing framework without a concrete need.

**Next step: exercise the shared tools and HTTP inspector, then decide whether editing/history views are needed.**
