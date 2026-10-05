# Jev / Laya API research

> Start with the concise [System One overview](System_One_Overview.md) and [typed model example](JEV_models.md).

**Scope.** This is a design/research note for the HTTP integration and using Jev to route work inside Yallmo's composed agents. Findings were collected on 2026-10-13.

**Implementation status.** The initial draft is now implemented as `SOMProvider` in `model`,
`JevHttpProvider` in `core`, and a non-text `SystemOneDecisionAgent` draft. The proposed names and pseudo-code below
remain useful historical context, but the current API is documented in [System_One_Overview.md](System_One_Overview.md)
and [JEV_models.md](JEV_models.md).

## Executive decision

**Do not implement Jev as an `LlmProvider`, `Agent`, or Docker Model Runner-compatible provider.** Jev is a System One *decision* model, not a chat/completion model: it cannot produce assistant text, stream, call tools, or run an agent turn. It therefore cannot satisfy Yallmo's `LlmProvider` / `AgentTurnRequest` / `AgentTurnResponse` contract.

Implement a small, separate **`SOMProvider`** boundary and an HTTP **Jev/Laya adapter**. Call it before a trusted, deterministic orchestration decision. The selected worker remains an existing LLM-backed `AgentInstance` run by `AgentRunner`.

There are two model planes:

| Plane | Responsibility | Configuration |
| --- | --- | --- |
| System One / Jev | Classifies, scores, and supplies probabilities over a closed answer space. | Jev endpoint, key, and one Jev model ID (normally `jev-latest`). |
| Worker LLMs | Generate text, request tools, and complete the actual agent work. | Existing Docker Model Runner / OpenAI / Gemini configurations and a locally owned worker/profile registry. |

So **no new set of chat models is needed for Jev**. Add one System One configuration and a deterministic mapping from Jev answers to the already configured worker agents/models. Jev itself exposes versioned aliases, not a collection of role-specific coding models. Its current public alias is `jev-latest` (currently resolving to `jev-1.13.0`); pin a version only when threshold calibration must not move.

---

# API integration

## 1.1 What the official API is

The official endpoint is a JSON evaluation API, not OpenAI Chat Completions:

```http
POST https://api.typesafe.ai/v1/systemone
Authorization: Bearer $TYPESAFE_API_KEY
Content-Type: application/json
```

```json
{
  "state": {
    "request": "billed twice, refund please",
    "order": {"duplicate_charge": true}
  },
  "model": "jev-latest",
  "questions": {
    "department": {
      "type": "choice",
      "instructions": "Which team should handle `request`?",
      "criteria": {
        "billing": "Refunds, invoices, payments, and duplicate charges",
        "technical": "Bugs, outages, and integrations"
      }
    },
    "urgency": {
      "type": "score",
      "instructions": "How urgent is `request`?",
      "criteria": ["routine", "soon", "urgent"]
    },
    "asks_for_refund": {
      "type": "noul",
      "instructions": "Does `request` ask for a refund?"
    }
  }
}
```

`state` is the material to judge: a string, structured JSON object, or array. Questions see the same state, run independently and in parallel, and return under their caller-selected IDs. Keep facts/context in `state`; make `instructions` and `criteria` narrow judgments. State and question instructions can be structured JSON, and instructions can refer to a state path with backticks, e.g. `` `request` ``.

The three supported primitives are:

| Primitive | Request `criteria` | Required response fields | Interpretation |
| --- | --- | --- | --- |
| `choice` | Map from closed option ID to description; max 255 options | `type`, `choice`, `probabilities`, `confidence` | One permitted option plus a distribution. |
| `score` | Ordered array of 2–10 level descriptions | `type`, `score`, `legend`, `probabilities`, `confidence` | Probability-weighted position, so it can be fractional between levels. |
| `noul` | Optional `{ "true": ..., "false": ... }` descriptions | `type`, `noul` | Probability of “yes”, from 0 to 1. Around 0.5 is uncertain; official Jev does not define a separate confidence field for this primitive. |

A normal response is:

```json
{
  "model": "jev-1.13.0",
  "answers": {
    "department": {
      "type": "choice",
      "choice": "billing",
      "probabilities": {"billing": 0.88, "technical": 0.12},
      "confidence": 0.76
    },
    "urgency": {
      "type": "score",
      "score": 1.05,
      "legend": {"0": "routine", "1": "soon", "2": "urgent"},
      "probabilities": {"0": 0.0, "1": 0.95, "2": 0.05},
      "confidence": 0.92
    },
    "asks_for_refund": {"type": "noul", "noul": 0.95}
  },
  "usage": {"input_tokens": 296, "output_tokens": 20}
}
```

Production errors to model explicitly: `401` (bad/missing key), `422` (invalid request), `429` (rate limit), and `529` (temporary overload). Retry only transient transport failures and `429`/`529`, with exponential backoff and `Retry-After` when supplied; do not retry malformed payloads or authentication failures.

`GET https://api.typesafe.ai/v1/models` is available on the official service to list account-visible aliases. It is useful for diagnostics, but must not be required at startup or on the request path.

### Important semantic limits

* Jev is text-only; it does not accept images, audio, video, tool definitions, chat history semantics, or a system prompt.
* It returns no prose explanation or reasoning. Answers are constrained to caller-supplied choices/rubrics.
* Current public docs list a 64k request context limit (state plus all questions) and a 32k limit for state plus the longest question. Keep the router state much smaller than that: the task, a bounded summary/excerpt, and the candidate metadata it needs.
* Choice/Score `confidence` measures how concentrated the returned distribution is; it is not a guarantee of correctness. Tune thresholds against Yallmo tasks. For high-impact selections, low confidence should take a deterministic safe route (default worker, confirmation, clarification, or escalation), not merely pick the top answer.

## 1.2 Is it similar to Docker Model Runner / Llama?

Only at the transport level: both are JSON-over-HTTP. They are protocol and semantic incompatibilities:

| Existing Docker Model Runner adapter | Jev / Laya |
| --- | --- |
| `POST .../chat/completions` | `POST .../v1/systemone` |
| OpenAI messages, system/user/assistant roles | One state object/string and a question map |
| Model produces assistant text and possibly tool calls | Model produces typed decisions only |
| `ModelRunnerClient` parses `choices[0].message` | New client parses `answers[questionId]` |
| Fits `LlmProvider` and `Agent[Any]` | Must not fit either interface |

The existing deferred OpenAI-compatible refactoring plan is therefore not the extraction point. A shared Chat Completions client would not reduce Jev/Laya code or make its contract safer.

## 1.3 Recommended Yallmo boundary

Add a provider-neutral decision contract, then an HTTP adapter. Keep it separate from the LLM turn interfaces.

```scala
// model: values deliberately contain no HTTP or provider names
trait SystemOneClient:
  def evaluate(request: SystemOneRequest): IO[SystemOneError, SystemOneResponse]

final case class SystemOneRequest(
  state: ujson.Value,
  model: String,
  questions: Map[SystemOneQuestionId, SystemOneQuestion]
)

sealed trait SystemOneQuestion:
  def instructions: ujson.Value
final case class ChoiceQuestion(
  instructions: ujson.Value,
  criteria: Map[String, ujson.Value]
) extends SystemOneQuestion
final case class ScoreQuestion(
  instructions: ujson.Value,
  criteria: Seq[ujson.Value]
) extends SystemOneQuestion
final case class NoulQuestion(
  instructions: ujson.Value,
  criteria: Option[Map[Boolean, ujson.Value]] = None
) extends SystemOneQuestion

final case class SystemOneResponse(
  model: String,
  answers: Map[SystemOneQuestionId, SystemOneAnswer],
  usage: SystemOneUsage
)
```

The exact JSON representation can use the project’s existing `ujson` in `core`; the public contract should still be typed enough to prevent mismatching a question and answer. In particular, validate before returning success:

1. non-empty URL/model/question IDs; a non-empty state;
2. Choice criterion IDs are non-empty and unique; Score has 2–10 levels;
3. response has every requested answer, no mismatched `type`, all numeric values are finite and within `[0, 1]` where applicable;
4. a Choice selected ID is in its submitted criteria and probability keys match the criteria (with a documented tolerance for sum-to-one rounding);
5. a Score is in `[0, criteria.size - 1]`; and
6. required `model`, `answers`, and `usage` values are present.

Unknown **additional** response fields must be ignored. This is necessary for Laya compatibility and future Jev response evolution, but must not weaken validation of fields used in routing.

Suggested implementation locations:

```text
model/.../systemone/SystemOne.scala              # neutral request/response/errors/client
core/.../provider/typesafe/JevSystemOneClient.scala
core/.../provider/typesafe/JevConfig.scala
core/.../provider/typesafe/JevJsonCodec.scala
core/src/test/.../provider/typesafe/JevSystemOneClientSuite.scala
```

`JevSystemOneClient` should use the existing ZIO HTTP `Client`, accept cancellation, have a short configurable request timeout, and log status/model/latency/usage but never the API key. It may expose an optional `listModels` diagnostic method, not make runtime availability depend on it.

### Configuration proposal

Use names that describe the new protocol rather than reuse `MY_MODEL_URL` / `MY_MODEL_NAME`:

```dotenv
# Official Jev
JEV_SYSTEM_ONE_URL=https://api.typesafe.ai/v1/systemone
TYPESAFE_API_KEY=...
# The model, for example `jev-latest`, belongs to every System One request.

# Local Laya experiment
JEV_SYSTEM_ONE_URL=http://host.docker.internal:8000/v1/systemone
# No key was required by the tested local service.
# The model, for example `jev-latest`, belongs to every System One request.
```

Make the API key optional at the generic client boundary: add `Authorization: Bearer ...` only when a non-blank key is configured. Official TypeSafe configuration should require it; local Laya is intentionally unauthenticated. Make that an explicit composition/configuration choice, never a production fallback. The `build.sbt` dotenv loader passes through `JEV_SYSTEM_ONE_URL` and `TYPESAFE_API_KEY` (without committing either value) for local run/test configuration.

## 1.4 Laya compatibility experiment

The user-supplied local endpoint was reachable at `http://host.docker.internal:8000` (not `localhost` from this environment). Two successful unauthenticated requests were made on 2026-10-13.

| Probe | Result |
| --- | --- |
| Supplied Choice request, no `model` field | `200` in 125.52 ms (`x-inference-time-ms`); `dept.choice = "billing"`. |
| Structured state with Choice + Score + Noul and `model: "jev-latest"` | `200` in 780.30 ms. It selected `review`, scored 1.0997/2, and returned Noul 0.8563. |
| `GET /v1/models` | `404 Not Found`; unlike official Jev, model discovery is unavailable here. |
| Empty `questions` malformed probe | no response before the client’s 15 second curl timeout. This is not a documented Jev behavior; enforce a client timeout and local pre-validation. |

Laya used the same essential envelope: `model`, `answers`, and `usage`. It returns additional fields which the adapter should ignore unless Yallmo deliberately adopts them:

```json
{
  "model": "laya-rl-agent",
  "answers": {
    "worker": {
      "type": "choice",
      "choice": "review",
      "probabilities": {"coding": 0.0047, "review": 0.9908, "research": 0.0045},
      "confidence": 0.9467,
      "answer_confidence": 0.9908,
      "action": {"act_probability": 1.0},
      "x_jev_confidence": 0.9862
    }
  },
  "usage": {
    "input_tokens": 211,
    "output_tokens": 0,
    "state_tokens": 33,
    "state_tokens_dropped": 0,
    "truncated": false,
    "truncated_questions": []
  },
  "routing": {"model": "english", "repo": "convaiinnovations/laya"}
}
```

Compatibility conclusion: **Laya is sufficiently compatible for a contract-testable System One adapter**, not evidence that it is a full official Jev implementation. In particular, it accepts an omitted model where the official API documents `model` as required, lacks `GET /v1/models`, adds non-standard fields (including Noul confidence), and did not promptly reject the malformed empty-question request. Tests must assert the stable subset and provider-specific behavior separately.

---

