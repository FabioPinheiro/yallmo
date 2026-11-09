# Hermes Context Engine and Dual Compression

Source: https://hermes-agent.nousresearch.com/docs/developer-guide/context-compression-and-caching

## Pluggable Context Engine

Hermes context management is built around `ContextEngine` in `agent/context_engine.py`.

The built-in default is `ContextCompressor`, but plugins can replace it.

A context engine is responsible for:

- deciding when compression should fire (`should_compress()`),
- performing compression (`compress()`),
- optionally exposing tools (`lcm_grep` or similar),
- tracking token usage from API responses.

## Engine Selection

Config:

```yaml
context:
  engine: "compressor" # built-in default
  # engine: "lcm"      # example plugin
```

Resolution order:

1. `plugins/context_engine/<name>/`,
2. general plugin system via `register_context_engine()`,
3. built-in `ContextCompressor` fallback.

Plugin engines are not auto-activated. User must explicitly configure `context.engine`.

## Dual Compression System

Hermes has two independent compression layers:

1. **Gateway Session Hygiene**
   - Runs before the agent processes a message.
   - Fixed threshold: 85% of model context.
   - Safety net for large sessions between turns.
   - Uses actual API-reported tokens when available; rough estimate otherwise.
   - Requires history length >= 4 and compression enabled.

2. **Agent ContextCompressor**
   - Runs inside the agent loop.
   - Default threshold: 50% of model context.
   - Uses more accurate token accounting from agent/model responses.
   - Primary normal context management path.

The gateway threshold is intentionally higher than the agent threshold to avoid premature compression on every turn.

## Compression Config

Typical defaults:

```yaml
compression:
  enabled: true
  threshold: 0.50
  target_ratio: 0.20
  protect_last_n: 20
auxiliary:
  compression:
    model: null
    provider: auto
```

Key parameters:

- `threshold`: prompt tokens >= threshold × context length triggers compression.
- `target_ratio`: tail token budget as fraction of threshold tokens.
- `protect_last_n`: minimum recent messages preserved.
- `protect_first_n`: hardcoded small head preservation, typically system + first exchange.

## Design Rules

- Compression is normal lifecycle behavior, not an emergency only.
- Gateway hygiene is a high-threshold safety net.
- Agent compression is lower-threshold and more accurate.
- A pluggable engine can replace lossy compression, but only if explicitly configured.
