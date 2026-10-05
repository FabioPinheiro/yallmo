# Rewrite-history feature — deferred plan

> **Status:** deferred. This is deliberately out of scope for the first Storage persistence implementation.

## Intent

An agent may decide that a failed attempt should no longer be present in the active conversation history. For example, after a failed function call it may retain the useful user request while starting a replacement path that omits the failed call and its output.

```text
u1  []       user-root       User("Get the weather for Faro")
c1  [u1]     researcher-v1  FunctionCall(weather, {"city":"Far"})
e1  [u1,c1]  researcher-v1  FunctionCallOutput(error)

# Future rewrite decision: retain u1, remove c1/e1 from active history.
c2  [u1]     researcher-v2  FunctionCall(weather, {"city":"Faro"})
o2  [u1,c2]  researcher-v2  FunctionCallOutput("22°C")
r1  [u1,c2,o2] researcher-v2 Assistant("Faro is 22°C")
```

The resulting active ref would point at `r1`; the failed attempt would not be selected as context for later messages.

## Boundary with persistence and materialization

This is the sole deferred plan for physical history rewriting. Phase 1 artifact codecs/hashes, Phase 2 object storage, Phase 3 refs, and Phase 4 runtime-only `RunContext` materialization must neither rewrite history nor add a rewrite fixture. They may select direct inputs that omit prior artifacts from a later prompt, but that is not physical rewriting.

## Why it is deferred

The initial Storage model is immutable and append-only:

- artifact IDs include their ordered input IDs, so an existing artifact cannot have a parent removed;
- `objects.jsonl` cannot delete an individual record while retaining append-only recovery guarantees;
- rewriting a history requires creating replacement descendants with new IDs and moving refs atomically;
- a persisted function call may have externally completed, so dropping it from history cannot undo its side effect; and
- branch refs, and concurrent sessions may still refer to the old graph.

Until this feature is designed, the system uses selected direct inputs to *omit* a failed attempt from future materialization while retaining it in the durable DAG for audit.

## Future design questions

1. Is rewriting only a logical ref move to replacement immutable artifacts, or may it physically remove unreachable objects?
2. If physical removal is allowed, what retention period, confirmation, backup/export, and garbage-collection process applies?
3. Must an audit record disclose the rewrite and its actor/reason, or is the old graph intentionally destroyed?
4. Which refs, and active sessions prevent a rewrite or deletion?
5. How are provider continuations, function-call/output pairing, and summaries rebuilt for replacement messages?
6. Which messages can be copied verbatim and which must be regenerated or explicitly approved by the user?
7. How are non-idempotent tool calls represented so that a rewrite never implies that an external operation was undone?
8. How does a crash during object compaction and ref replacement recover safely?

## Future delivery outline

1. Define user-visible rewrite semantics and authorization.
2. Define a pure replacement-graph planner with golden fixtures.
3. Implement ref-safe creation of replacement artifacts; do not delete old artifacts yet.
4. Add reachability analysis across all refs and trace references.
5. Only after retention and recovery policies are accepted, implement an opt-in vacuum/compaction operation.

No production code, schema field, automatic rewrite, or Phase 4 materializer fixture is introduced by this plan.
