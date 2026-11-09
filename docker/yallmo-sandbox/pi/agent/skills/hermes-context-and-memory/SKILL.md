---
name: hermes-context-and-memory
description: Use for Hermes agent loop, context compression, prompt caching, memory correctness, memory_tool behavior, session search, and Pi Hermes Memory concepts.
---

# Hermes Context and Memory

Read relevant references before answering/implementing:
- `references/01-agent-loop.md`
- `references/02-compression-engine.md`
- `references/03-compression-algorithm.md`
- `references/04-prompt-caching.md`
- `references/05-memory-correctness.md`
- `references/06-pi-hermes-memory.md`
- `checklists/context-memory-checklist.md`

Core model: memory and context are separate but coordinated. Prompt assembly snapshots memory; the loop flushes memory before context loss; compression preserves recent/tool-call integrity while summarizing older history; search tools retrieve context on demand.

Rules:
- Distinguish prompt memory snapshots from on-disk memory.
- Do not claim writes instantly mutate an already-built system prompt.
- Treat compression as lossy unless a lossless context engine is explicit.
- Be precise about thresholds, cache boundaries, and native Pi vs upstream Hermes behavior.
