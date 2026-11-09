# Hermes Prompt Assembly Checklist

Use when reviewing or porting Hermes-style prompt assembly.

## Stable System Prompt

- [ ] Agent identity is loaded first.
- [ ] `SOUL.md` replaces default identity when present.
- [ ] Tool-aware behavior guidance is included after identity.
- [ ] Memory/user snapshots are loaded at prompt-build/session start.
- [ ] Skills index is compact, not full skill bodies by default.
- [ ] Project context file priority is deterministic.
- [ ] Context files are scanned and truncated before injection.

## Memory Correctness

- [ ] Mid-session memory writes update storage but do not silently mutate current cached prompt.
- [ ] Memory is compact, durable, and non-secret.
- [ ] User profile is separated from general memory.
- [ ] Procedural knowledge goes to skills, not memory bloat.

## Ephemeral Boundaries

- [ ] Gateway overlays are per-call only.
- [ ] Prefill messages are per-call only.
- [ ] Context/budget warnings are not permanently appended.
- [ ] Recall/search results are framed as context, not new user instructions.

## Cache Safety

- [ ] Stable prompt prefix is preserved across turns.
- [ ] Message ordering is not changed unnecessarily.
- [ ] Compression behavior is understood to invalidate part of cache but preserve stable system cache where possible.

## Output Guidance

When explaining Hermes prompt assembly, report:

1. Stable layer order.
2. Ephemeral layers.
3. Memory snapshot semantics.
4. Context file priority.
5. Prompt caching implications.
