# Hermes Context and Memory Checklist

Use when reviewing, implementing, or explaining Hermes-style context and memory systems.

## Agent Loop

- [ ] Prompt is built/reused before provider call.
- [ ] Provider-specific API formats converge to one internal message format.
- [ ] Message alternation rules are preserved.
- [ ] Tool calls and results are appended in valid order.
- [ ] Parallel tool results are reinserted in original call order.
- [ ] Agent-level tools like memory/session_search are handled deliberately.

## Compression

- [ ] Compression threshold is clear, typically 50% in-agent.
- [ ] Gateway hygiene is a separate high-threshold safety net, typically 85%.
- [ ] Old verbose tool results can be pruned cheaply.
- [ ] Head and recent tail messages are protected.
- [ ] Tool call/result groups are not split.
- [ ] Summary has structured sections.
- [ ] Previous summaries are updated during re-compression.
- [ ] Summary model context window is large enough.
- [ ] Memory is flushed before context is lost.

## Prompt Caching

- [ ] Stable system prompt prefix is preserved.
- [ ] Ephemeral overlays are outside cached prompt state.
- [ ] Anthropic cache-control breakpoints follow system + rolling recent messages strategy when applicable.
- [ ] Compression cache invalidation is understood.

## Memory Correctness

- [ ] Memory/user prompt snapshots are frozen for the current built prompt.
- [ ] Mid-session memory writes update storage, not hidden prompt state.
- [ ] Memory contains durable compact facts, not secrets or transcripts.
- [ ] Procedures are saved as skills, not bloated memory entries.
- [ ] Memory/search results are context, not higher-priority instructions.

## Pi Hermes Memory

- [ ] Distinguish Pi extension behavior from upstream Hermes internals.
- [ ] Policy-only mode vs legacy injection is understood.
- [ ] Global vs project memory tier is understood.
- [ ] SQLite session/memory search is on-demand retrieval.
- [ ] Secret scanning and auto-consolidation are considered.

## Final Output

When answering, include:

1. Which system is being discussed: Hermes upstream or Pi Hermes Memory.
2. Loop/compression/caching implications.
3. Memory correctness implications.
4. Risks and anti-patterns.
