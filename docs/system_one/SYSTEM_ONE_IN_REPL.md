# System One in the line REPL — reviewed plan

## Decision

Use **`/so`**, not `/som`.

Start with one direct command:

```text
/so run <body>
```

`<body>` is the complete System One request as one JSON value. The REPL parses and validates it locally, then explicitly evaluates it through `SOMProvider`.

Add an optional interactive builder behind:

```text
/so request
```

The builder is a local feature of the JLine terminal client. It is a local modal REPL for composing the same JSON request; its `run` action delegates to the exact same `/so run <body>` path. It is not a chat-agent conversation. The builder obtains its required model from System One REPL configuration, so it has no user-facing `model` command; it prints the resolved model when the builder starts.

No implementation is covered by this document.

## Why this shape

The direct form is the smallest useful and most transparent feature:

- users can paste a request from the API playground, a file, or another program;
- exactly one command performs the network call;
- the request does not enter the LLM transcript or `AgentRunner`;
- the builder remains convenience UI, not a second execution model.

A user-built request has runtime-defined question IDs and types. It should therefore be represented internally as:

```scala
SystemOneRequest[zio.json.ast.Json]
```

This has the same wire JSON envelope as `SORequest`, but it is the correct dynamic model. `SORequest[S, Q]` needs a compile-time case-class question schema and `SOSchema`; an arbitrary terminal user cannot create that static type.

## Direct command: `/so run <body>`

Example:

```text
/so run {"state":{"task":"Route a refund request","workers":["billing","support"]},"model":"jev-latest","questions":{"worker":{"type":"choice","instructions":"Which worker should handle `task`?","criteria":{"billing":"Refunds and charges","support":"General support"}},"needs_clarification":{"type":"noul","instructions":"Is essential information missing?","criteria":{"true":"Ask the user a question","false":"The task is actionable"}},"urgency":{"type":"score","instructions":"How urgent is `task`?","criteria":["routine","soon","urgent"]}}}
```

The command grammar is deliberately simple:

```text
/so run <complete-json-request>
/so request
/so help
```

`<complete-json-request>` means the entire untrimmed command tail after `run`; it is **not** a sequence of shell-like arguments. This avoids a separate quoting language. The JSON parser handles spaces and strings normally. Multi-line pasted JSON and file input are not part of the first increment; users can submit compact JSON or use the request builder. A later convenience form may support `/so run @file.json`, with an explicit local-file read and the same validation path.

`/so run` must:

1. reject a missing body;
2. parse one JSON value and reject trailing non-whitespace content;
3. strictly validate/decode the request envelope as `SystemOneRequest[Json]`;
4. apply local request validation;
5. make one explicit `SOMProvider.evaluate(request)` call;
6. render a System One result, not an assistant turn.

It must **not** hand the body to the chat model, turn it into `AgentTask`, change the active LLM/model/tools, or infer a request from ordinary prompt text.

### Local request validation

Before calling a provider, validate the supported protocol subset:

- root object has `state`, non-blank `model`, and a non-empty `questions` object;
- every question has exactly one supported primitive: `choice`, `score`, or `noul`;
- every question has non-blank text `instructions`;
- Choice has a non-empty object of non-blank criterion IDs and descriptions;
- Score has a non-empty ordered array of non-blank level descriptions;
- Noul either has no criteria or has both non-blank `true` and `false` criterion descriptions;
- the encoded request is below a conservative configured size limit.

The parser should reject a malformed or unsupported body rather than silently dropping fields or accepting a `type` inconsistent with the question body. It should report the JSON path and a concise correction. For example:

```text
/so: questions.worker.criteria must be a non-empty object for a choice question.
```

This is request-shape validation, not a claim to prove every provider semantic. A provider error still remains possible. Never send a request that fails local validation.

## `/so request`: local request-builder REPL

`/so request` enters a temporary nested REPL in the JLine client. It uses a distinct prompt, for example:

```text
yallmo> /so request
System One request builder. Type help for commands; cancel leaves without sending.
so>
```

The builder does not call an LLM and does not automatically include the chat transcript. It holds an in-memory draft only for the lifetime of that builder session; `state-run-context` is the explicit opt-in that snapshots the outer `RunContext` into the draft state.

Suggested builder language:

| Builder input | Meaning |
| --- | --- |
| `help` | Show builder commands and a short example. |
| `state <json>` | Set state to one JSON value. A plain string is entered as JSON, for example `state "review this change"`. |
| `state-run-context` | Set state to a JSON representation of the complete current outer `RunContext`. |
| `choice <id> "<instructions>" <criteria-json-object>` | Add/replace a Choice question. |
| `score <id> "<instructions>" <criteria-json-array-of-string>` | Add/replace a Score question. |
| `noul <id> "<instructions>"` | Add/replace a Noul question. |
| `remove <id>` | Remove a question. |
| `show` | Pretty-print the complete request JSON and a copy-pasteable `/so run <body>` form. |
| `run` | Validate, convert the draft to the canonical full JSON body, and execute through `/so run`. |
| `cancel` | Discard the draft and return to `yallmo>`; ask for confirmation when the draft is non-empty. |

Example composition:

```text
so> state {"task":"Review this change","changedFiles":7}
so> choice worker "Which worker should handle `task`?" {"review":"Audit a change","coding":"Implement code"}
so> noul needs_review "Does `task` need review?"
so> score complexity "How difficult is `task`?" ["simple","bounded","complex"]
so> show
so> run
```

`<instructions>` is a JSON string literal, not shell quoting: it must begin and end with `"`, and its escapes follow JSON rules. The final criteria argument is likewise parsed as one complete JSON value. The builder supplies the question `type`; users provide only the criterion value. In particular, builder Noul questions have no criteria. Question IDs should follow a small documented builder-only grammar such as `[A-Za-z][A-Za-z0-9_-]{0,63}`. `/so run` may accept any valid protocol IDs; the builder can be intentionally more ergonomic and restrictive.

`state-run-context` takes no argument. Before replacing state, it prints a warning and asks for confirmation: this operation sends the complete current conversation transcript to the System One provider when the user later runs the request. On confirmation, it replaces the draft state with the whole current outer `RunContext`, represented as `{"messages":[...]}` using the existing `MessagePayload` JSON encoding. It includes every transcript message, not only text prompts, and does not include `StaticContext` or session-storage metadata. Because messages can contain sensitive and large tool or retrieval payloads, `show` must make that visible and normal request-size validation can reject it; the builder must not silently summarize or truncate it.

The configured builder model is inserted when the draft is created, printed in the builder header, and visible in `show`. If it is absent, `/so request` must fail before starting the nested loop. `/so run <body>` remains a full request API and therefore receives its model from `body`.

`run` must not duplicate provider execution logic. It serializes the draft as the full canonical body and invokes the same application operation used by `/so run <body>`. Thus direct input and builder input have identical validation, provider selection, error handling, and result rendering.

### Line-UI scope

The nested input loop, its `so> ` prompt, its history behavior, and its completion are client concerns. Initially implement them only in `TerminalRepl`:

- `/so request` causes an application event expressing “start System One request builder”; it does not block or read from `ReplApplication`.
- `TerminalRepl` handles that event with the modal builder loop using its JLine reader.
- The builder has a dedicated parser and JLine completer; help, parsing, and completion derive from its one command-definition list.
- Ctrl-C cancels the current builder line. Leaving the builder requires `cancel` or EOF; neither sends a request. The normal double-Ctrl-C exit behavior remains owned by the outer REPL.

The direct `/so run <body>` operation can remain application-level and renderer-neutral. Initial product support may be exposed only from the line UI, but its semantics must not be embedded in JLine so a future TUI/client can use the same operation.

## Integration boundary

System One is a decision API, not an `LlmProvider`, an `Agent`, or an `AgentRunner` tool.

The proposed responsibilities are:

```text
root grammar /so run body
          │
          ▼
System One request decoder + validator
          │
          ▼
SOMProvider.evaluate(SystemOneRequest[Json])
          │
          ▼
System One result event and renderer
```

`/so request` is only an alternate way to produce the `body` before entering this path.

The REPL composition root must receive a separately configured `SOMProvider`; the current REPL composition only supplies an LLM provider. If no System One provider is configured, `/so run` and `/so request` should fail clearly before any draft/network action, for example:

```text
/so is unavailable: no System One provider is configured.
```

Use a System One-specific REPL event/result rather than `AgentTurn`. Normal output should include the returned provider model, usage, and the answers by ID and primitive. For Choice and Score, show confidence/probabilities when supplied; for Noul, show its true probability. Warn visibly if a compatible provider reports truncation. Do not put raw request or response bodies in normal debug capture or history by default.

## Safety and lifecycle rules

- `/so run` is an explicit network and data-disclosure action. No command should auto-run after `show`, `state`, `state-run-context`, a question edit, or builder entry.
- Do not log credentials. Treat request state as potentially sensitive.
- Do not persist builder drafts to durable REPL sessions or global JLine history in the first version. Explicit export/persistence is separate future work.
- On malformed input, authentication failure, or an invalid provider response, do not retry automatically. Bounded retry policy for transient provider failures belongs at the provider boundary.
- Validate dynamic responses against the submitted question IDs and expected primitive types before presenting them as a successful result. A malformed/mismatched response is a protocol failure.
- An answer is informational only. It cannot select arbitrary agents, models, tools, permissions, or execute an action. Any future routing must use the closed-registry, confidence-gated host policy in `ORCHESTRATION_RESEARCH.md`.

## Delivery order

1. **Direct run:** add `/so help` and `/so run <body>` parsing; strict JSON/envelope validation; a separately injected `SOMProvider`; System One result/error events; and line-renderer output.
2. **Direct-run tests:** valid Choice/Score/Noul bodies; invalid JSON; missing/invalid fields; no provider call after validation failure; exactly one provider call after a valid command; response type/ID mismatch; provider and truncation failures.
3. **Line builder:** implement `/so request` and its modal parser, completion, cancellation confirmation, configured model, in-memory draft, `state-run-context` confirmation, copy-pasteable `show`, and delegation to direct run. Add tests for the builder state machine and that `run` uses the direct-run path.
4. **Later, only if needed:** safe request export/import, multi-line JSON entry, `/so run @file.json`, and a TUI-native builder.

This order creates a useful transparent primitive first, then adds the line-only convenience layer without introducing a parallel System One execution path.
