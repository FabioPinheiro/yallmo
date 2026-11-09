# Ephemeral Layers and Prompt Cache Boundaries

Source: https://hermes-agent.nousresearch.com/docs/developer-guide/prompt-assembly

## API-Call-Time-Only Layers

Hermes intentionally keeps these out of the cached system prompt:

- `ephemeral_system_prompt`,
- prefill messages,
- gateway-derived session context overlays,
- later-turn Honcho recall injected into current-turn user message,
- budget/context pressure additions.

These are turn-scoped and should not poison persistent prompt state.

## Why This Matters

Separating stable and ephemeral layers helps:

- preserve provider-side prompt caching,
- avoid mutating conversation history unnecessarily,
- keep memory semantics understandable,
- let gateway/ACP/CLI add context without making it permanent,
- avoid stale warnings or temporary context leaking into future turns.

## Cache-Safe Design

Stable cached prefix:

- identity,
- behavior guidance,
- static memory snapshot,
- user profile snapshot,
- skills index,
- repo context files,
- timestamp/session/platform hints.

Dynamic per-call additions:

- current budget state,
- current context pressure,
- temporary recall/search results,
- gateway overlays,
- prefill/injection for one provider call.

## Practical Porting Pattern

When implementing Hermes-like prompt assembly:

1. Build a stable prompt prefix once.
2. Store version/hash metadata if useful.
3. On each model call, compose API messages from:
   - stable prompt,
   - conversation history,
   - ephemeral overlays.
4. Do not rewrite old history unless compression/compaction explicitly requires it.
5. If compression happens, accept that cache for the compressed region is invalidated while preserving the stable system prompt if possible.

## Anti-patterns

- Rebuilding the whole system prompt after every memory write.
- Injecting temporary gateway state as durable prompt text.
- Appending context warnings to history repeatedly.
- Letting search/recall results masquerade as new user instructions.
- Storing long procedural instructions in memory instead of skills.
