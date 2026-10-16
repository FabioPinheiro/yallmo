# OpenAI / ChatGPT subscription provider

## Intent and billing boundary

Use the user's eligible ChatGPT subscription in Yallmo, just as pi.dev does. **No OpenAI API key, no separately billed platform API, no automatic fallback to a paid API.** Authentication and a live smoke test require the user; implementation/tests must not read real tokens or make authenticated calls.

Pi calls this provider `openai-codex`, but displays **OpenAI (ChatGPT Plus/Pro)** and marks it `isSubscription: true`. The word Codex here identifies the subscription transport, not API-key billing. Account entitlements, available models and subscription usage limits remain controlled by OpenAI. This is not access to every OpenAI API product (audio/TTS remain separate).

## Research completed before implementation

Primary implementation inspected: installed `@earendil-works/pi-ai` **1.2.0**, under `/usr/local/lib/node_modules/@earendil-works/pi-coding-agent/node_modules/@earendil-works/pi-ai/`.

- `dist/auth/oauth/openai-codex.js`: browser authorization-code + PKCE (S256), state, refresh tokens; optional headless device-code flow. Client ID `app_EMoamEEZ73f0CkXaXp7hrann`; issuer `https://auth.openai.com`; browser callback `http://localhost:1455/auth/callback`; scopes `openid profile email offline_access`. Pi offers manual pasted redirects when callback delivery is unavailable.
- `dist/api/openai-codex-responses.js`: subscription endpoint `https://chatgpt.com/backend-api/codex/responses`; OAuth bearer token plus `chatgpt-account-id` extracted from the token's `https://api.openai.com/auth` claim; SSE requests use `OpenAI-Beta: responses=experimental`. Requests set `store: false`, `stream: true`, a nonempty top-level `instructions`, and `include: ["reasoning.encrypted_content"]`. Terminal event variants include `response.done`, `response.completed`, `response.incomplete`; failed events must fail the turn.
- Pi docs: https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/providers.md ; corresponding source paths are `packages/ai/src/auth/oauth/openai-codex.ts` and `packages/ai/src/api/openai-codex-responses.ts`. Findings above are tied to the installed version, not assumed stable public API guarantees.
- Existing Yallmo `core/.../provider/openai/OpenAiCodex{Auth,Secrets,Client,TurnMapper}.scala` already implements this subscription backend; do not duplicate it or invoke the Pi CLI. Existing tests: `OpenAiCodexSuite`.
- Existing gaps: client model hardcoded to `gpt-5.6-terra`; structured turns omit required top-level instructions and encrypted-content include; SSE lacks `response.done` and uses incorrect multiline joining; delta-only assistant text is lost in structured turns; auth device polling retries all HTTP errors; no auth request timeout; credentials may be exposed by generated case-class `toString`; no user-facing login entry point; REPL only composes the OAI adapter.
- Current composition: `ReplMain` hardcodes `OAILlmProvider`/`OAIAgent` and requires `MY_MODEL_URL`/`MY_MODEL_NAME`. `AgentRunner` must retain sole ownership of native turn tool execution. Voice transcription uses its own OAI configuration and must remain independently optional.

## Revision: REPL-native `/login` (requested by user)

The standalone login main is rejected and superseded by a REPL command. Keep the existing OAuth service and credential format, but move user interaction into the REPL clients.

- [x] L1 Add `/login` (browser default) and `/login device`, grammar/help/completion and typed dispatch.
- [x] L2 Run browser/manual redirect and device flow inside plain and JLine clients; keep secret input out of command history, transcripts, persistence, debug captures and logs. Cancellation/EOF must return safely to the REPL without an agent call.
- [x] L3 Remove `OpenAiSubscriptionLogin.scala`; update setup docs and stale standalone-main references.
- [x] L4 Add offline command/login/UI regressions, run full module tests and targeted formatting.
- [ ] L5 Parent performs independent final-diff review (required acceptance gate).
- [x] L5 Resolve independent-review P1: preserve ordinary plain-input tabs, unterminated final lines at EOF, and prompts beyond the private-login input limit; add regressions and revalidate.
- [ ] U2 User verifies `/login` and a live subscription turn (not automated).

**Ownership preflight:** this revision is one tightly coupled REPL command/interaction seam, not a rewrite of OAuth or provider protocol. One worker exclusively owns the main checkout `/home/fmgp/workspace/yallmo` while implementing grammar, semantic dispatch and both local terminal interpreters together; splitting would overlap command/event contracts. It may delete only the superseded standalone main in core, add/update relevant REPL tests and docs, and make a minimal auth-service API adjustment only if essential. Parent remains read-only until handoff. Preserve all existing uncommitted provider work; no reset, commits, live login or secret-file reads. Gate: command parsing/completion, injected offline auth interactions, state/history isolation, full regressions and parent acceptance. Durable handoff: checkout diff plus worker report. Login does not implicitly switch an OAI session's selected generation provider.

## Default-model update

The user selected `gpt-6.1-sol` as the ChatGPT subscription default, replacing the initial `gpt-5.6-terra` default. Explicit `--model` overrides and the independent OAI configuration remain unchanged.

## Design and scope

Complete the existing native adapter, expose a clearly named **ChatGPT subscription** option, and preserve the existing OAI default.

1. Auth: retain current private, atomically updated `.env` credentials for compatibility, preserve unrelated entries, redact sensitive `toString`, validate browser callback/state/errors, bound auth HTTP calls/device polling, classify pending/slow-down/fatal polling results, and serialize refresh within one auth instance. Do not import or modify Pi/Codex credentials. Multi-process shared-file refresh locking is deferred and documented.
2. Protocol: allow explicit nonblank model selection (availability is checked by the service, not a static allowlist); preserve the existing default for compatibility. Lift the canonical final instruction item into the required top-level field (Yallmo appends instructions last), keep any earlier instruction changes in order using developer messages, request encrypted reasoning replay, normalize supported SSE terminal events and fail malformed/truncated/failed streams. This initial adapter buffers SSE per turn; interactive token streaming/WebSockets/retries/model discovery are out of scope.
3. UX (revised): expose browser/manual redirect and optional device login via `/login` inside the existing REPL, with no additional main and no implicit login/network on startup. Add `--provider=chatgpt` (alias `openai-codex`) and optional `--model=<id>` while defaulting to existing OAI. Provider selection must not require an OAI URL when ChatGPT is selected. Do not switch transcription to subscription billing or make audio mandatory.
4. Tests: synthetic credentials, pure request/parser tests and fake/local HTTP where useful; no real `.env` access or live subscription calls. Preserve OAI behavior and existing tests.

## Task tracker

- [x] R1 Inspect Pi docs/source and establish subscription vs API-key distinction.
- [x] R2 Inspect Yallmo provider/agent/REPL contracts and existing tests.
- [x] P1 Record design, acceptance criteria, ownership and deferred scope before coding.
- [x] A1 Harden OAuth validation/polling/refresh and credential handling; add focused tests.
- [x] A2 Original standalone login implementation (superseded and removed by L1–L3).
- [x] B1 Complete request mapping, configurable model selection and SSE parsing; add focused tests.
- [x] C1 Integrate explicit provider/model selection in REPL, preserving OAI and optional voice.
- [x] C2 Document login, startup, billing boundary, credentials, limits and smoke test.
- [x] V1 Run focused tests, broader module regression tests, and scalafmt (inspect unrelated changes).
- [x] V2 Fresh read-only review, resolve findings and record verification evidence.
- [ ] U1 User performs browser/device login and a live text + approved tool round trip (not automated).

## Implementation ownership / handoffs

Multi-seam: auth and protocol have independent contracts and test gates. They are isolated, then integrated by the parent. Workers must not commit, read `.env`, call authenticated services, edit outside their files, or spawn children.

| Lane | cwd / isolation | Exclusive files/decision | Gate / durable handoff |
| --- | --- | --- | --- |
| A — OAuth/login (completed, integrated) | `/tmp/yallmo-subscription-auth` detached worktree at baseline `c207610` | `OpenAiCodexAuth.scala`, `OpenAiCodexSecrets.scala`, superseded standalone login (removed by L3), new `OpenAiSubscriptionAuthSuite.scala` | focused core tests; diff retained in worktree, parent transfers only claimed files |
| B — protocol (completed, integrated) | `/tmp/yallmo-subscription-protocol` detached worktree at baseline `c207610` | `OpenAiCodexClient.scala`, `OpenAiCodexTurnMapper.scala`, `OpenAiCodexSuite.scala` | focused core tests; diff retained in worktree, parent transfers only claimed files |
| C — integration (completed) | main checkout; parent only after A/B handoffs | REPL selection/composition/tests, build dotenv allowlist only if needed, docs; integration fixes after ownership release | all focused + regression tests and fresh review |

## Acceptance

- User can log in using their eligible ChatGPT account without `OPENAI_API_KEY`, then select ChatGPT in the REPL without local OAI generation configuration.
- Native turns preserve text, tool call IDs/results and same-provider encrypted reasoning; no provider-side execution of tools.
- Required subscription request fields/headers are correct and selected model is honored.
- OAuth state/expiry/pending/fatal cases, failed/truncated SSE, and provider option errors have offline regression coverage.
- Tokens/authorization codes are not printed/logged by application errors or credential rendering; credential writes remain private and atomic.
- Existing default OAI mode remains compatible; subscription failures do not fall back to an API-key provider.

## Verification / remaining limitations

A/B workers completed and handed off their exclusive source diffs; the parent integrated all seven files (including new untracked additions). Parent integration added provider options/composition, six REPL tests and a fake-HTTP native two-turn test covering OAuth headers, selected model, tool output and encrypted reasoning replay.

Verification so far:
- Final explicit full-module run (`sbt ';model/testOnly *;core/testOnly *;repl/testOnly *'`) passed: **model 10, core 87, REPL 27 — 124 distinct tests**, including the fake-HTTP native round trip. REPL aggregation reruns core tests; those repeats are not counted twice. Log: `/tmp/yallmo-subscription-final-regression.log`.
- Focused provider run passed **35 tests** after the reasoning summary correction. Log: `/tmp/yallmo-subscription-final-focused.log`.
- All changed/new Scala files formatted with targeted `scalafmtOnly` calls; no unrelated formatter rewrites. Final `git diff --check` passed (using a command-local `safe.directory` for the checkout's `/Users/...` alias).
- Historical standalone-login help validation is superseded by REPL-native login tests (L4); the standalone main has been removed.
- Subscription plain-UI startup with `MY_MODEL_URL` and `MY_MODEL_NAME` removed from the fork environment accepted `/exit` and exited successfully, without authenticated calls.
- Historical standalone stdin probe is superseded by shared-reader plain-client pipe/login regression tests (L4).
- Fresh independent reviewer confirmed a required reasoning replay `summary` field was missing in the legacy mapper. Parent added a summary array (including the empty case) and regression assertions. Reviewer statically rechecked the fix, reported no remaining findings and returned **OK with notes**; focused/full tests subsequently passed.

Live account validation intentionally remains unchecked until the user runs it. No promise that the configured default model is enabled for every subscription; choose an available model with `--model`. Deferred: callback HTTP listener, token-by-token UI streaming/WebSockets, model discovery, automatic transport retries and cross-process refresh locking.


### REPL-native login revision verification

- `/login` and `/login browser` dispatch a browser login request; `/login device` dispatches a device request. Help/Tab completion derive from the grammar. The application emits only a typed event with the exact same state; login cannot invoke agents/tools, persist a turn, reset the selected session, change its transcript/log level or switch generation providers.
- `SubscriptionLogin` delegates OAuth/credential handling to the unchanged `OpenAiCodexAuth`. Both clients own a separate private interaction, with no new main, dependencies, auth protocol, or unsafe runtime boundary. The superseded standalone source was removed. Existing uncommitted provider changes were preserved.
- The shared terminal adapter bypasses the JLine editing buffer/widgets/completion and disables history while masking login input. Terminal attributes and the prior history variable are restored on every outcome. A bounded nonblocking read plus atomic per-character bookkeeping prevents a cancelled race from leaving a callback fragment (or stripped command prefix) as agent input. Timeout partial-line remainders are discarded before ordinary input resumes. Plain pipes use the same reader, with no competing/orphan stdin consumer or persistent history.
- Offline tests cover grammar/invalid arguments/completion/help, exact state and storage isolation, browser/device success and instruction ordering, sanitized errors/defects, cancellation/EOF, polling finalizers, whole-flow timeout, masked prompt/history-file/buffer isolation, attribute restoration, partial-line discard, ordinary plain `/help` + `/exit` pipes and return to commands after browser login.
- Focused run: `sbt 'repl/testOnly *ReplSuite *SubscriptionLoginSuite *TerminalLoginInputSuite'` passed **25 tests**. Log: `/tmp/yallmo-repl-login-focused.log`.
- Explicit full-module regression, repeated after formatting: `sbt ';model/testOnly *;core/testOnly *;repl/testOnly *'` passed **model 10, core 87, REPL 40 — 137 distinct tests** (aggregated core repeats excluded). Logs: `/tmp/yallmo-repl-login-full.log`, `/tmp/yallmo-repl-login-final-full.log`.
- Targeted `scalafmtOnly` used exact absolute paths for the 10 changed/new main REPL files and 3 test files, with task aggregation temporarily disabled. No core/unrelated source formatting. Log: `/tmp/yallmo-repl-login-format.log`. `git diff --check` passed; nothing staged.
- Remaining manual checks: real interactive TTY rendering and user-authenticated browser/device login plus subscription turn. No credentials were inspected and no live OAuth/authenticated calls were made during implementation/tests. Plain unsupported-TTY echo limitations, up-to-30-second initial device-code request cancellation delay, 15-minute flow bound and partial-line clearing behavior are documented. Type subsequent commands only after the ordinary prompt returns. Callback listener and cross-process credential locking remain deferred as before.

### L5 plain-reader P1 verification

- Kept the shared terminal reader; ordinary reads now preserve tabs/control text, return nonempty final text before EOF, and have no private-login length cap. Masked login reads retain cancellation/EOF discard, the 16384-character bound, history/masking safeguards and unfinished-input clearing.
- Added three offline regressions: direct ordinary reader cases (including EOF on the next read), private length-limit/discard protection, and plain-client pipe dispatch of unterminated `/help`, `/log` TAB `debug`, and a 20000-character prompt reaching a fake agent at Debug level.
- Focused tests passed before and after exact-path formatting: **28 tests**, including **10 TerminalLoginInputSuite tests**; existing SubscriptionLoginSuite/ReplSuite remain green. Logs: `/tmp/yallmo-p1-focused.log`, `/tmp/yallmo-p1-final-focused.log`.
- Explicit full module regression passed: **model 10, core 87, REPL 43 — 140 distinct tests** (aggregated core repeat excluded). Log: `/tmp/yallmo-p1-full.log`.
- Exact-path scalafmtOnly with aggregation disabled touched only the two owned Scala files. Log: `/tmp/yallmo-p1-format.log`. Initial formatter invocation required semicolon-separated commands and was corrected; subsequent formatting and affected tests passed. `git diff --check` passed; nothing staged.
- Parent final review accepted the P1 correction: ordinary EOF/tabs/long text are now separate from masked private-input rules, with direct reader and end-to-end plain-client regressions. Verified full/focused test logs and `git diff --check`; the superseded standalone main is absent. No remaining review findings. Live account/TTY validation remains manual; no network/authentication or real credential access performed.
