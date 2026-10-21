# Native Provider-Turn Agent Loop — Completed

> This implementation plan is complete. It remains as a historical delivery record; active architecture documentation belongs in [Agent orchestration](../core/src/main/scala/fmgp/yallmo/orchestration/ORCHESTRATION.md).
> Names and tool/persistence limits below describe that delivery, not today's APIs. For current audio retention and diagnostics, see [Attachments and blob storage](../docs/orchestration/ATTACHMENTS_AND_BLOB_STORAGE_PLAN.md).

## Delivered

- Canonical provider-neutral protocol in `fmgp.yallmo.protocol`:
  - ordered conversation items;
  - opaque provider and function-call IDs;
  - provider continuations;
  - recursive JSON schemas;
  - native turn request/response, usage, and stop-reason models.
- `LlmTurnClient` performs one provider turn only; it does not execute tools or loop.
- OpenAI Codex Responses and Docker Model Runner implement native turn serialization and parsing.
- `NativeFunctionAgent` projects the immutable orchestration transcript into provider input.
- `AgentRunner` remains the sole owner of tool lookup, schema validation, approval, execution, budgets, transcript updates, and repetition.
- Native function calls and outputs retain their linking call IDs. OpenAI output-item IDs are preserved separately.
- Tool failures, denials, unknown/unadvertised tools, and invalid arguments become linked tool-output error messages, allowing the model to recover.
- The REPL uses the native loop and exposes workspace-confined read-only `readFile` and `listFiles` tools.
- Native debug records use a stable turn ID and write correlated redacted request/response JSON files under `debug/`.
- `/debug` retains transcript JSONL output and reports the latest native capture.
- Legacy JSON-decision support remains as a compatibility path. `generateWithTools` is deprecated and has no native-loop caller.

## Intentional boundaries

- Provider continuations are opaque, replayed only by their originating provider, and redacted from logs.
- Tool calls run sequentially by default.
- Mutating and shell REPL tools are not registered; any future capability of that kind must be approval-protected.
- Streaming UI events, durable sessions, context compaction, parallel tool execution, and Gemini native turns are separate future initiatives, not unfinished work from this plan.

## Validation

Relevant suites cover protocol validation, provider mappers, native call/output ordering, rejection and recovery behavior, REPL behavior, and debug redaction.
