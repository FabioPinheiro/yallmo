---
name: hermes-prompt-assembly
description: "Use for Hermes prompt assembly: cached system layers, SOUL identity, memory/user snapshots, skills index, context files, ephemeral overlays, and cache-safe prompt design."
---

# Hermes Prompt Assembly

Read relevant references before answering/implementing:
- `references/01-layer-order.md`
- `references/02-identity-context-files.md`
- `references/03-memory-snapshots-skills.md`
- `references/04-ephemeral-cache-boundaries.md`
- `checklists/prompt-assembly-checklist.md`

Hermes separates stable cached prompt state from turn-scoped ephemeral overlays.

Stable prefix may include identity, behavior, memory/user snapshots, skills, context files, timestamp/session, and platform hints. Ephemeral additions include budget/context-pressure warnings, prefill/gateway overlays, and later-turn recall.

Rules:
- Do not model prompt assembly as one mutable string.
- Keep cache-sensitive prefixes stable.
- Treat memory snapshots as frozen for a built session.
- Keep ephemeral overlays out of persistent prompt state.
- Adapt concepts to Pi/other harnesses instead of copying internals blindly.
