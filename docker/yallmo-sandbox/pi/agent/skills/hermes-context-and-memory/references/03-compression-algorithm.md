# Hermes Compression Algorithm

Source: https://hermes-agent.nousresearch.com/docs/developer-guide/context-compression-and-caching

Hermes' built-in `ContextCompressor.compress()` uses a four-phase algorithm.

## Phase 1: Prune Old Tool Results

Old tool results outside the protected tail are cheaply replaced without an LLM call when they are large.

Example replacement:

```text
[Old tool output cleared to save context space]
```

Purpose:

- save tokens from verbose file contents, terminal output, search results,
- reduce context before LLM summarization,
- preserve recent tool results where they are most likely needed.

## Phase 2: Determine Boundaries

Message history is divided into:

```text
[head]      protected first messages
[middle]    summarized region
[tail]      recent messages preserved intact
```

Tail protection is token-budget based: walk backward from the end until the budget is exhausted. If this protects too few messages, fall back to `protect_last_n`.

Boundaries align around tool-call/tool-result groups. Hermes avoids splitting an assistant tool call from its corresponding tool result.

## Phase 3: Generate Structured Summary

The middle region is summarized by an auxiliary LLM using a structured template:

```markdown
## Goal
## Constraints & Preferences
## Progress
### Done
### In Progress
### Blocked
## Key Decisions
## Relevant Files
## Next Steps
## Critical Context
```

Summary budget:

- roughly 20% of compressed content tokens,
- minimum around 2,000 tokens,
- maximum `min(context_length × 0.05, 12,000)`.

Critical failure mode: the compression model must have enough context for the middle section. If its context window is too small, summary generation may fail and the compressor can drop middle turns without summary, losing context.

## Phase 4: Assemble Compressed Messages

The compressed list contains:

1. head messages,
2. a summary message,
3. tail messages unmodified.

Hermes chooses summary message role to avoid role alternation violations.

It also sanitizes tool pairs:

- remove tool results whose tool call was removed,
- inject stub results when a tool call survived but result was removed.

## Iterative Re-compression

On later compressions, Hermes passes the previous summary to the summarizer and asks it to update rather than start over.

This allows:

- old in-progress work to move to done,
- new progress to be added,
- obsolete context to be removed,
- continuity across multiple compactions.

## Compression Correctness Rules

- Flush memory before compression to avoid losing durable facts.
- Preserve recent tail intact.
- Keep tool call/result groups together.
- Preserve first messages/system prompt.
- Prefer structured summaries over free-form summaries.
- Treat built-in compression as lossy.
