# Hermes Agent Loop

Source: https://hermes-agent.nousresearch.com/docs/developer-guide/agent-loop

## Core Class

Hermes' core orchestration engine is `run_agent.py`'s `AIAgent` class.

It handles:

- prompt assembly and tool schemas via `prompt_builder.py`,
- provider/API mode selection,
- interruptible model calls,
- tool execution,
- OpenAI-style conversation history,
- compression, retries, and fallback model switching,
- parent/child iteration budgets,
- persistent memory flushing before context is lost.

## Entry Points

Two main user-facing entry points:

```python
response = agent.chat("Fix the bug in main.py")
```

`chat()` is a thin wrapper returning final response text.

```python
result = agent.run_conversation(
    user_message="Fix the bug in main.py",
    system_message=None,
    conversation_history=None,
    task_id="task_abc123",
)
```

`run_conversation()` returns messages, metadata, usage stats, and final response.

## API Modes

Hermes normalizes multiple provider modes into one internal OpenAI-style message format:

- `chat_completions` — OpenAI-compatible endpoints.
- `codex_responses` — OpenAI Codex / Responses API.
- `anthropic_messages` — native Anthropic Messages API through adapter.

Resolution order:

1. explicit `api_mode`,
2. provider-specific detection,
3. base URL heuristics,
4. default `chat_completions`.

## Turn Lifecycle

Each loop iteration roughly follows:

1. Generate `task_id` if needed.
2. Append user message to history.
3. Build or reuse cached system prompt.
4. Check preflight compression (>50% context by default).
5. Build provider-specific API messages.
6. Inject ephemeral prompt layers.
7. Apply prompt caching markers when Anthropic supports it.
8. Make interruptible API call.
9. Parse response:
   - if tool calls exist, execute tools, append results, continue loop;
   - if text response, persist session, flush memory if needed, return.

## Message Alternation

Hermes enforces provider-safe role ordering:

- System, then User → Assistant → User → Assistant...
- Tool use: Assistant with tool calls → Tool result(s) → Assistant.
- No two assistant messages in a row.
- No two user messages in a row.
- Consecutive tool messages are allowed for parallel tool results.

## Agent-Level Tools

Some tools are intercepted by `run_agent.py` before normal registry dispatch:

- `todo` — agent-local task state.
- `memory` — persistent memory files and limits.
- `session_search` — session DB query.
- `delegate_task` — subagents with isolated context.

## Tool Execution

- Single tool call: direct execution.
- Multiple tool calls: concurrent execution with `ThreadPoolExecutor`.
- Interactive tools force sequential execution.
- Tool results are reinserted in original call order.

## Persistence

After each turn:

- session messages are saved,
- memory changes are flushed to `MEMORY.md` / `USER.md`,
- sessions can be resumed later.
