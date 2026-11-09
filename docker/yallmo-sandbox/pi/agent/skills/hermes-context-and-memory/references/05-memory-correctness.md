# Memory Correctness and memory_tool Semantics

Sources:

- https://hermes-agent.nousresearch.com/docs/developer-guide/prompt-assembly
- https://hermes-agent.nousresearch.com/docs/developer-guide/agent-loop

## Frozen Snapshot Rule

Hermes injects `MEMORY.md` and `USER.md` as frozen prompt snapshots when the system prompt is built.

Mid-session writes:

- update disk state,
- may be flushed after turns or before compression,
- do **not** mutate the already-built cached system prompt.

New sessions or forced prompt rebuilds see updated memory snapshots.

This prevents confusion where the agent writes a memory and then assumes it has magically appeared in the current hidden prompt.

## Memory Tool Role

Hermes intercepts the `memory` tool at the agent level before normal registry dispatch.

It writes durable memory/user facts with limits and returns synthetic tool results.

Use memory for stable facts:

- user preferences,
- environment details,
- tool quirks,
- project conventions,
- durable lessons/failures.

Do not use memory for:

- secrets,
- one-off temporary task state,
- full transcripts,
- long procedures better represented as skills,
- unverified assumptions.

## Flush Before Context Loss

The agent loop is responsible for flushing persistent memory before context is lost, especially around compression. This ensures durable facts extracted from soon-to-be-summarized turns are saved.

## Session Search

When the user references past conversations or cross-session context may exist, Hermes can use `session_search` to retrieve relevant history instead of asking the user to repeat themselves.

Session search is context retrieval, not instruction injection.

## Memory Correctness Hierarchy

When memory conflicts with current evidence:

1. Current user instruction wins unless unsafe/impossible.
2. Current repo/tool evidence wins over stale memory.
3. Project context wins over general preferences for project-specific work.
4. Memory is useful context, not absolute truth.

## Prompt Assembly Interaction

Memory appears in prompt assembly as a snapshot layer. The memory tool updates future snapshots and searchable memory, but current prompt semantics remain stable.

## Review Questions

- Is this fact durable enough to save?
- Is it compact?
- Is it free of secrets?
- Is it better represented as a skill/procedure?
- Does saving it create a future prompt-injection risk?
- If the current turn needs it, should the agent also mention/use it explicitly rather than relying on future prompt injection?
