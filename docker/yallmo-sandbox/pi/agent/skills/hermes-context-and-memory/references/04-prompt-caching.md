# Hermes Prompt Caching

Source: https://hermes-agent.nousresearch.com/docs/developer-guide/context-compression-and-caching

## Anthropic Prompt Caching

Hermes uses Anthropic `cache_control` markers to reduce input token costs on multi-turn conversations.

Prompt caching is enabled when:

- the model is an Anthropic Claude model,
- the provider supports `cache_control` through native Anthropic API or OpenRouter,
- prompt caching config allows it.

Config:

```yaml
prompt_caching:
  cache_ttl: "5m" # or "1h"
```

## Strategy: system_and_3

Anthropic allows up to 4 cache-control breakpoints. Hermes uses:

1. System prompt — stable across turns.
2. Third-to-last non-system message.
3. Second-to-last non-system message.
4. Last non-system message.

This creates a stable system cache plus a rolling recent-message cache window.

## Marker Shape

Typical marker:

```json
{ "type": "ephemeral" }
```

For longer TTL:

```json
{ "type": "ephemeral", "ttl": "1h" }
```

Hermes adapts placement based on content shape:

- string content may be converted to text blocks with `cache_control`,
- list content gets marker on the last item,
- empty content may get marker on the message,
- tool messages may get marker when native Anthropic supports it.

## Cache-Aware Design Patterns

- Keep the system prompt stable.
- Avoid mutating the middle of history.
- Do not inject ephemeral overlays into stable prompt state.
- Preserve message ordering.
- Recognize compression invalidates cache for compressed regions.
- The system prompt cache can survive compression when stable.
- Rolling 3-message cache re-establishes within 1–2 turns after compression.

## TTL Choice

- `5m`: default, good for active sessions.
- `1h`: better when users pause between turns.

## Context Pressure Warnings

Hermes removed intermediate context-pressure warnings because they caused models to give up prematurely on complex tasks. Compression fires at configured threshold without earlier warnings; gateway hygiene remains the high-threshold safety net.

## Anti-patterns

- Rebuilding the stable system prompt every turn.
- Inserting temporary recall/context into the system prompt.
- Reordering old messages unnecessarily.
- Treating cache markers as semantic model instructions.
