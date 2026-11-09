# Memory Snapshots and Skills Index

Source: https://hermes-agent.nousresearch.com/docs/developer-guide/prompt-assembly

## Frozen Memory Snapshots

Hermes injects local memory and user profile data as frozen snapshots at session start / prompt-build time.

Important semantics:

- `MEMORY.md` and `USER.md` are read into the cached system prompt.
- Mid-session memory writes update disk state.
- Those writes do **not** mutate the already-built cached system prompt.
- New sessions or forced prompt rebuilds see updated snapshots.

This is a memory-correctness design choice: the model should not assume that a tool write instantly changes the hidden system prompt for the current call.

## MEMORY vs USER

- `MEMORY.md`: durable facts, preferences, environment details, tool quirks, stable conventions.
- `USER.md`: profile information about the user.

These are prompt inputs, not direct replacements for evidence. Repo files, tool output, and current user instructions still matter.

## Skills Index

Hermes contributes a compact skills index to the prompt when skills tooling is available.

Typical instruction shape:

```text
Before replying, scan the skills below. If one clearly matches your task, load it with skill_view(name) and follow its instructions.
```

The prompt includes a compact list of available skills, not necessarily all full skill contents.

## Memory + Skills Division

Use memory for durable facts:

- user prefers pnpm,
- project uses pytest,
- tool X requires flag Y,
- previous failure and why it happened.

Use skills for reusable procedures:

- how to review a PR,
- how to debug a recurring build issue,
- how to run a deployment checklist,
- how to use a project-specific workflow.

## Correctness Rules

- Do not store secrets or transient task details as durable memory.
- Do not treat recalled memory as a higher-priority instruction than current user intent or project evidence.
- Keep memory compact and stable.
- Use skills for long procedural knowledge instead of bloating memory snapshots.
