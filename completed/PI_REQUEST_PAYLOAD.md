# Understanding Pi's LLM Request Payload

This document explains the request captured in [`PI_EXAMPLES.MD`](../docs/examples/PI_EXAMPLES.MD). The JSON object beginning with `{"model": ...}` is a **logical provider request body** produced by Pi for an OpenAI Codex Responses model.

> **Privacy:** a captured payload can contain the full system prompt, user messages, file contents, command output, and tool schemas. Do not commit or share it without reviewing it.

## What Pi sends

The example uses Pi's `openai-codex-responses` adapter with `gpt-5.6-terra`. Its body follows the OpenAI **Responses API** shape:

```json
{
  "model": "gpt-5.6-terra",
  "store": false,
  "stream": true,
  "instructions": "...",
  "input": ["..."],
  "tools": ["..."],
  "tool_choice": "auto",
  "parallel_tool_calls": true
}
```

It is not an Ollama-native request. Pi converts the same internal conversation to a different shape when the selected provider uses OpenAI Chat Completions, Anthropic Messages, Google Generative AI, or another supported adapter.

## 1. Request controls

```json
{
  "model": "gpt-5.6-terra",
  "store": false,
  "stream": true
}
```

- `model` selects the provider model.
- `store: false` asks the service not to persist the generated response as server-side stored response state.
- `stream: true` makes the response arrive as incremental events.

Pi normally tries a WebSocket transport for this Codex adapter. The literal WebSocket message adds an envelope:

```json
{ "type": "response.create", "...": "the request body" }
```

When Pi uses its HTTP/SSE fallback, it POSTs the body itself. The body may be compressed in transit. A `before_provider_request` extension observes the logical JSON body, not authentication headers or transport compression.

## 2. `instructions`: the system prompt

```json
"instructions": "You are an expert coding assistant operating inside pi..."
```

`instructions` is the Responses API's system/developer instruction channel. It is semantically Pi's **system prompt**, rather than a normal conversation item.

Pi assembles it from its base prompt and runtime configuration, including:

- the agent's role and coding rules;
- available core tools and their usage rules;
- project and working-directory information;
- documentation and skill locations;
- orchestration, safety, and editing guidance.

It explains how the model can operate as an agent. For example, it tells the model that `bash`, `read`, `edit`, and `write` exist and how they must be called.

## 3. `input`: conversation plus tool-loop state

```json
"input": [ ... ]
```

`input` is ordered context. It can contain more than chat messages.

### User message

```json
{
  "role": "user",
  "content": [{ "type": "input_text", "text": "hi" }]
}
```

This is a normal user prompt.

### Assistant message

```json
{
  "type": "message",
  "role": "assistant",
  "content": [{ "type": "output_text", "text": "Hi! How can I help?" }],
  "status": "completed",
  "id": "msg_..."
}
```

Pi includes previous assistant text so that the model retains conversational context.

### Reasoning continuation

```json
{
  "type": "reasoning",
  "id": "rs_...",
  "encrypted_content": "...",
  "summary": [{ "type": "summary_text", "text": "**Searching for target file**" }]
}
```

- `encrypted_content` is opaque provider-managed reasoning continuation state. Pi cannot read it. Pi preserves and replays it so a reasoning model can continue correctly after a tool call or in a later turn.
- `summary` is a short readable summary requested from the model. It is not the complete private chain of thought.

The request option below asks the provider to return the opaque continuation data:

```json
"include": ["reasoning.encrypted_content"]
```

### Model-requested function call

```json
{
  "type": "function_call",
  "call_id": "call_...",
  "name": "bash",
  "arguments": "{\"command\":\"find ...\"}"
}
```

This is a prior model decision. The model does not run the command itself; Pi runs it locally.

### Result supplied by Pi

```json
{
  "type": "function_call_output",
  "call_id": "call_...",
  "output": "./.pi/agent/extensions/provider-payload.ts\n"
}
```

Pi returns local execution output to the model. `call_id` associates the result with the original function call.

## 4. Tool definitions and selection

```json
{
  "tool_choice": "auto",
  "parallel_tool_calls": true,
  "tools": [ ... ]
}
```

- `tools` contains the currently active Pi tools, each represented as an OpenAI function definition with a JSON Schema parameter object.
- `tool_choice: "auto"` lets the model decide whether it should answer with text or request a tool.
- `parallel_tool_calls: true` lets the model request independent tool calls in one response.

The values and function-schema format are part of the OpenAI Responses API. The general idea—models request tools and the host executes them—is shared by other providers, but their exact JSON differs.

## 5. Output and reasoning preferences

```json
{
  "text": { "verbosity": "low" },
  "reasoning": { "effort": "high", "summary": "auto" }
}
```

- `text.verbosity` requests the desired length of visible model text.
- `reasoning.effort` selects the model's reasoning level.
- `reasoning.summary` requests a readable reasoning summary when the provider supports it.

## 6. Prompt caching

```json
"prompt_cache_key": "01a10de4-..."
```

This is a stable key that helps the provider reuse repeated prompt prefixes, particularly the large `instructions` string and tool definitions. It is not user content and is not the conversation itself.

## 7. The agent loop

A task needing tools commonly produces multiple provider requests:

```text
user prompt
  -> Pi sends instructions + prior input + tools
  -> model returns text and/or function_call items
  -> Pi executes requested calls locally
  -> Pi sends function_call and function_call_output items back to the model
  -> repeat until the model returns its final text
```

The payload in `PI_EXAMPLES.MD` is one request in that loop. Its final items are tool outputs, so the next model response will normally interpret those outputs and decide whether to make another call or answer the user.

## API compatibility

| Pi API type | Typical request format |
| --- | --- |
| `openai-responses` / `openai-codex-responses` | Responses API: `instructions`, `input`, `tools` |
| `openai-completions` | Chat Completions: `messages`, `tools` |
| `anthropic-messages` | Anthropic Messages: `system`, `messages`, `tools` |
| `google-generative-ai` | Gemini `contents` and `tools` format |
| Ollama native | Ollama `/api/chat` format |

Ollama's OpenAI-compatible endpoint is usually configured in Pi as `openai-completions`; it does not automatically support every Responses API field.

## References

- Pi model and provider configuration: [`docs/models.md`](/usr/local/lib/node_modules/@earendil-works/pi-coding-agent/docs/models.md)
- OpenAI Responses API create reference: <https://platform.openai.com/docs/api-reference/responses/create>
- OpenAI function calling guide: <https://platform.openai.com/docs/guides/function-calling>
- OpenAI reasoning guide: <https://platform.openai.com/docs/guides/reasoning>
