# System One / Jev

System One is a constrained **decision** protocol. It evaluates shared state against caller-defined Choice, Score, and Noul questions; it does not produce chat text, tool calls, or an agent turn.

## Architecture

```text
bounded task state + trusted question schema
                  |
                  v
          Jev / compatible Laya
                  |
                  v
validated typed answers + deterministic host policy
                  |
                  v
existing AgentRunner and selected worker LLM
```

Jev may select or score only values that Yallmo supplied in a closed schema. The host owns worker profiles, models, tools, budgets, approvals, confidence thresholds, and fallbacks. It must not treat a Jev result as an authorization decision.

## Yallmo model layers

- Use `SystemOneRequest[State]` and its `Map[String, SystemOneQuestion]` questions when IDs are assembled dynamically.
- Use `SORequest[State, Questions]` plus `Questions derives SOSchema` for a fixed case-class schema and typed answer access.
- Both forms encode the same System One request envelope, including all question `type` hints. Fixed-schema JSON
  encoding now requires `SOSchema[Questions]` even when directly calling `toJson`; use the dynamic model for arbitrary
  runtime question objects. Custom encoders retain their payloads/aliases but must match the fixed schema's object
  shape and cannot supply conflicting/duplicate hints. See [encoding contract](MANAGED_HISTORY_DECISIONS.md#typed-question-encoding)
  and [JEV_models.md](JEV_models.md).

## Provider boundary

`SOMProvider` is the provider-neutral equivalent of `LlmProvider`, without pretending that System One is text
completion. `evaluate(SystemOneRequest)` returns a decoded wire response for dynamic schemas;
`evaluate(SORequest)` additionally applies `SOSchema` and returns typed `SOAnswer` fields. A provider adapter has one
transport seam: it sends the encoded request and decodes the successful `SystemOneResponse`.

## Opt-in agent programs

[`SystemOneHistoryDecision`](MANAGED_HISTORY_DECISIONS.md) composes one advisory typed
Keep/Rebase decision with an agent-selected managed-history candidate and a fresh
request for the existing conversational agent. It is not automatic cleanup or model
authorization. The usage example, conservative host policy and generic typed wire contract are documented there.
Independent review and an explicitly opted-in synthetic-data live run are separate from automated fixtures

## Runtime configuration

`JevHttpProvider` reads the required full endpoint from `JEV_SYSTEM_ONE_URL`, for example
`https://api.typesafe.ai/v1/systemone`. It sends `Authorization: Bearer …` only when the optional
`TYPESAFE_API_KEY` is set; an omitted key is reserved for an explicitly configured local Laya service. The initial
provider has a 20-second timeout and no automatic retries. Callers decide whether a failed decision should trigger a
safe deterministic fallback.

Managed preparation uses captured message-head identity, not a rewrite counter: a different local head stops stale
work, while exact restoration of the original head/immutable ancestry is valid. The runner advances guards only from
its own atomic appends; session pointer competition remains independent. See the
[usage and head-identity policy](MANAGED_HISTORY_DECISIONS.md#small-host-owned-policy).

## Manual local smoke test

With Laya listening on `host.docker.internal:8000`, run:

```bash
sbt 'core/Test/runMain fmgp.yallmo.provider.jev.MainJevHttpProvider'
```

This is intentionally a manual test, not CI: it sends a real Choice, Noul, and Score request to the local endpoint
and prints the decoded response. It sends no authorization header.

## Documentation

- [Typed Yallmo model example](JEV_models.md)
- [Jev/Laya API research](JEV_API_RESEARCH.md)
- [Orchestration research](ORCHESTRATION_RESEARCH.md)

## External references

- [TypeSafe API reference](https://docs.typesafe.ai/api)
- [TypeSafe System One primitives](https://docs.typesafe.ai/primitives)
- [TypeSafe state concept](https://docs.typesafe.ai/concepts/state)
- [Decision API playground](https://decisions-api.dev/playground)
- [Jev with coding agents](https://docs.typesafe.ai/introduction/coding-agents)

The playground and external documentation are research aids; Yallmo's wire models and tests define the behavior currently supported here.
