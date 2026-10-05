# Storage artifact graph — design draft

> **Status:** proposed graph-first persistence model. No persistence code exists yet.

## Goal

A **Storage** is Yallmo's local, Git-like database for many agent conversations. A named session is a ref/branch into an immutable message DAG. Agents can split from any message and later use another branch as context.

The graph itself is the durable history. The Storage also owns mutable pointers and operational configuration needed to open and run work; those are not graph vertices.

## Two domain artifact types and one storage wrapper

There is deliberately no separate commit, summary, or artifact domain type at this stage. `AgentArtifact` and `MsgArtifact` are immutable values without self-assigned IDs. `StoredArtifact[A]` is the generic content-addressed persistence wrapper used to write either value to `objects.jsonl`; it is not a third domain artifact type.

### `AgentArtifact`

An immutable version of an agent definition. It is the configuration provenance for messages created by that agent.

It is self-contained and has this v1 shape (all collections are `Chunk`):

```scala
AgentArtifact(
  kind: AgentArtifactKind, // principal | llm
  agentId: AgentId,
  systemPrompt: String,
  skills: Chunk[AgentSkill],
  tools: Chunk[AgentToolDefinition],
  retrievers: Chunk[RetrieverDefinition],
  budget: AgentBudget,
  generateOptions: GenerateOptions,
  model: Option[String],
  providerId: Option[ProviderId],
  basedOnAgentArtifactId: Option[ArtifactId]
)
```

`AgentId`, `AgentSkill`, `AgentToolDefinition`, `RetrieverDefinition`, `AgentBudget`, and `GenerateOptions` are reused directly from orchestration; they are not copied into `*Manifest` types.

A `principal` artifact shares this simple common shape, but must have no provider, model, or `basedOnAgentArtifactId`. An `llm` artifact must have nonblank provider ID and model. Both require valid budget/options, nonblank capability keys, and a nonblank system prompt. `generateOptions.systemInstruction` must be absent: model-facing instructions are represented only by `systemPrompt`, while skills remain separate instructions. User-authored messages use the single principal artifact as their author.

It does **not** contain API keys, credentials, live `AgentInstance` values, provider clients, tools, retrievers, extensions, ZIO services, closures, executable tool code, decision codecs, or approval decisions. Those are resolved from the current trusted runtime when an agent is run.

`basedOnAgentArtifactId` is provenance only. It says which definition inspired a new agent definition; it does not provide inherited fields. Every `AgentArtifact` is self-contained so it can be inspected and validated without loading another agent artifact.

### `MsgArtifact`

An immutable vertex in the context graph:

```scala
MsgArtifact(
  agentId: ArtifactId,
  parentId: Option[ArtifactId],
  timestamp: Instant,
  message: Message
)
```

`agentId` identifies the exact immutable agent configuration that created or owns the message; despite its short name, it is the content address of an `AgentArtifact`, not the mutable `AgentId` display name. `parentId` is the sole direct message parent; `None` denotes a root. A branch may have many children, so the stored graph is a directed acyclic graph (more specifically, a forest of parent chains).

Every existing `Message` is valid payload: `User`, `Assistant`, `RagResult`, `ToolResult`, `FunctionCall`, `FunctionCallOutput`, and `Reasoning`. Function failures remain ordinary `FunctionCallOutput` or `ToolResult` payloads, using the existing normalized error text; this design does not introduce a `FunctionOutcome` type.

Future context-control concepts such as replies, summaries, compaction, and runtime errors must be added as explicit `Message` cases only after their model-context semantics are defined. They do not require another artifact type. Automations may create root message artifacts with no conversation ancestry. They affect a conversation only once another message references them.

A graph vertex needs no stored `msgId` field. Its identity is supplied by its enclosing `StoredArtifact[MsgArtifact]`.

### `StoredArtifact[A]`

The generic storage object adds a derived, verifiable address without making the address part of the domain value being hashed:

```scala
final case class StoredArtifact[A](
  id: ArtifactId,
  value: A
)
```

`StoredArtifact[MsgArtifact]` is the addressed graph vertex. `StoredArtifact[AgentArtifact]` is the addressed immutable agent definition. The stored `id` is useful for indexing and references but is recomputed and verified while loading; it is never accepted as an independently chosen value.

## Graph shape and context assembly

Each message records at most one direct predecessor in `parentId`. Roots have no parent; every other message names one already-stored valid message artifact. This creation-order rule prevents cycles. Branching is represented by multiple messages naming the same parent. Fan-in/merges are intentionally not represented in the message graph.

Context reconstruction follows the parent chain from a ref tip back to its root, then materializes the resulting root-to-tip sequence. The conversion of that sequence into an LLM request must be deterministic for a given runtime policy, including traversal, truncation, tool-call/output pairing, and provider-continuation filtering.

A later materializer may improve its policy without rewriting history. The historical graph remains valid even if a new runtime produces a different prompt.

Example parent chain:

```text
u1  None  User("Get the weather for Faro")
c1  u1    FunctionCall(weather, {"city":"Far"})
e1  c1    FunctionCallOutput(error JSON)
c2  e1    FunctionCall(weather, {"city":"Faro"})
o2  c2    FunctionCallOutput("22°C")
r1  o2    Assistant("Faro is 22°C")
```

A new branch can name any earlier message as its parent. There are no weak references: a parent is an immutable part of its child's provenance.

## Storage: mutable state outside the DAG

`Storage` is the preferred domain name. It corresponds roughly to a local Git repository and has a random `StorageId`; it is not content-addressed.

```text
~/.yallmo/
  universes/
    <universe-id>/
      objects.jsonl               # immutable StoredArtifact[AgentArtifact|MsgArtifact] values

      refs/
        sessions/                 # sessions; each file points to one ArtifactId
          main
          research-x
        agents/                   # optional long-lived agent work pointers
          researcher-1

      logs/                      # optional runtime logs, not owned by Storage
        repl.log
        agent-runners/
```

There are no reflogs in the first version. Ref movement history can be added later if required. V1 supports the `refs/sessions` and `refs/agents` namespaces with one safe nonblank name segment per file. A ref contains one full `ArtifactId` plus a newline. Ref updates validate the already-stored target, force a sibling temporary file, and atomically rename it; unsupported atomic rename fails rather than weakening the contract. A Storage is single-caller: concurrent processes must not share the same root.

Runtime bindings are supplied by the application that opens the Storage. `AgentArtifact` remains the immutable record of what a message expected.

## Persistent storage and recovery

`objects.jsonl` is the authoritative append-only object database. Every non-empty line is one strict `StoredArtifact` JSON object containing `id`, `kind`, and `value`. V1 accepts only `kind` `agent` or `msg`; unknown kinds fail loading. The stored ID is verified by recomputing the SHA-256 of its canonical JSON envelope before an object becomes available to the Storage.

Persistence rules:

1. An artifact may reference only parent/agent artifact IDs that already exist in the local object index. This creation-order invariant makes graph cycles impossible; hashing provides integrity but is not the cycle-prevention mechanism.
2. Append exactly one newline-terminated JSON object and flush the file channel before releasing the object for reference.
3. Write an object and flush it **before** atomically replacing a ref that points to it. A crash may leave an unreachable object, but never a ref to an absent object.
4. On load, reject a malformed non-final object line, duplicate ID with different canonical value, invalid hash, or missing referenced object. An incomplete final line without a trailing newline is treated as a torn append, ignored, and reported as recovery information.
5. Create Storage directories owner-only and object/ref files owner-read/write where the platform supports it. Prompts, tool output, and provider continuations are sensitive.

`StorageStore` owns object indexing, JSONL append/reload validation, ref resolution/update, and recovery diagnostics. Terminal UI, agents, tools, and provider adapters must not write Storage files directly.

## Tool execution and recovery

`FunctionCall` and `FunctionCallOutput` remain message artifacts. Persist the call artifact **before** execution and the output artifact only after execution produces a normalized result.

On recovery, a reachable call with no matching output is an **unknown-completion** operation, not proof that the tool did not run. Default behaviour must not auto-run it. The implementation may append a matching error/unknown-completion output, wait for the user, or create an explicit retry message. Timeouts and tool-specific idempotency policies belong to the trusted runtime/tool configuration, not to the provider.

## Provider continuations and provider changes

`Message.Reasoning` may contain provider-owned encrypted continuation state. It remains an ordinary message artifact for audit, but a materializer may replay it only when the current `AgentArtifact` uses the owning provider and the materialized main lineage satisfies that provider's continuation requirements.

A different provider must omit the continuation. To preserve useful context before changing provider, create a normal message artifact containing a provider-generated or user-approved summary while the old provider remains available, then branch from that message. No new artifact type is required.

## Content-addressed IDs

Use SHA-256 for `StoredArtifact` identities. The wrapped domain artifact does not contain its own ID. The sole durable representation is lowercase `sha256:` followed by 64 hexadecimal characters; abbreviated or uppercase forms are display-only/invalid: 

```text
ArtifactId = sha256(canonicalJsonUtf8({
  "kind": artifactKind,
  "value": A
}))

StoredArtifact(id = ArtifactId, value = A)
```

So the ID is the SHA-256 of the canonical JSON representation of `A`, wrapped with its kind. The canonical JSON codec must define stable field ordering, UTF-8, explicit enum tags, normalized timestamps, and no omitted/default fields. The `id` field itself is excluded from the hash.

For a `MsgArtifact`, the canonical bytes include `parentId`, exact `agentId`, timestamp, and `Message` payload. Consequently, identical text with different parents is a different addressed graph vertex; this is correct because its provenance differs.

A JSONL line stores the redundant address for indexing and validation:

```json
{
  "id": "sha256:...",
  "kind": "msg",
  "value": {
    "agentId": "sha256:...",
    "parentId": "sha256:...",
    "timestamp": "...",
    "message": { "kind": "assistant", "text": "Hello" }
  }
}
```

Benefits:

- IDs verify object integrity after reload;
- creation-order validation requires parents to be immutable prior objects, preventing cycles;
- writing the same canonical artifact is idempotent; and
- no separate random message-ID allocator is needed.

Constraints:

- changing the canonical encoding changes IDs;
- all hashes must be full-length in durable storage (short forms are display-only);
- object hashes can reveal equality and enable guessing of tiny known payloads. Storage files are private, but exported IDs should be treated as potentially sensitive; and
- random/non-deterministic encryption changes the bytes and therefore the ID, which is acceptable for a new immutable artifact.

## Open concerns for future planning

- Define the first graph-materializer contract and golden fixtures. It must state exactly how parent-chain traversal, token limits, and tool call/output pairing become a provider request.
- Define how current runtime registrations verify the accepted self-contained `AgentArtifact` schema's provider and tool compatibility without restoring authority from disk. Unavailable historical bindings are inspect-only; no fallback runtime is selected.
- Define ref naming, ownership, and safe atomic update semantics if concurrent agents are introduced.
- Define retry/unknown-completion semantics for non-idempotent tools, including user approval and tool-specific idempotency keys.
- Define record-size limits plus encryption/export, retention, reachability analysis, and deletion/garbage-collection policy. Until then, provider continuations remain opaque `Reasoning` payloads protected by owner-only files where supported and excluded from default diagnostics.
- Define how compaction or summarization can be represented using only `MsgArtifact` without corrupting history or pretending an LLM-generated summary is user input.
