# ChatGPT subscription provider

Yallmo can use the same subscription-backed OAuth flow as pi.dev's **OpenAI (ChatGPT Plus/Pro)** provider. It does **not** use `OPENAI_API_KEY`, the separately billed OpenAI platform API, or Pi as a subprocess. Internally this provider is called `openai-codex` because it talks to the ChatGPT Codex subscription backend. A subscription that includes access to that service is required; models and usage limits depend on your account.

## 1. Start the existing REPL, then log in

Run from the repository root, with sbt attached to your terminal. Credentials are **not** required to start subscription mode:

```bash
sbt --server -no-server 'repl/run --provider=chatgpt --ui=line'
```

At the `yallmo>` prompt, enter:

```text
/login
```

`/login browser` is the explicit equivalent. Open the printed authorization URL and sign in with your ChatGPT account. After approval, copy the **entire** `http://localhost:1455/auth/callback?...` address into the separate hidden redirect prompt, including state. Do not paste it into an ordinary `yallmo>` command prompt. This implementation does not start a callback server, so the localhost page may fail to load; its address still contains the result. The state must match the original request.

For headless/SSH environments, use this command in the same REPL (when device login is enabled by OpenAI):

```text
/login device
```

Visit the displayed verification URL, enter the pairing code and approve access. Instructions are displayed before polling begins. No standalone login application or second CLI is required.

Login is bounded to 15 minutes; individual authentication HTTP operations time out after 30 seconds. At the redirect prompt, empty input, `cancel`, Ctrl+C or EOF cancels. During authorization exchange/device polling, Enter, Ctrl+C or EOF cancels; any entered cancellation line is consumed in full. During the initial device-code HTTP request cancellation is processed after that bounded request returns. Wait for the normal prompt before typing another REPL command. After a timeout with partially entered input, finish that line with Enter when asked: its remainder is discarded, never submitted to the agent. Cancellation/EOF does not itself exit the REPL; a subsequent EOF at the ordinary prompt exits normally.

JLine masks redirect input, disables history for the private interaction and bypasses normal command editing, widgets and completion. Terminal/history settings are restored on every outcome. Plain mode (`--ui=plain`) has no persistent history and handles login separately from command submission, but cannot guarantee terminal echo suppression on unsupported terminals: prefer `/login device` there. Do not share redirects or record your login terminal. OAuth codes/tokens and failure details are never printed by the login handler.

`/login` works in OAI-selected sessions too, but **does not switch the selected generation provider**, reset the session, modify its transcript/log level or run any agent/tools. It only saves ChatGPT subscription credentials for subsequent subscription requests.

## 2. Continue subscription-backed generation

After successful login, enter a normal prompt in that same subscription REPL. To explicitly choose a model available to your account on startup (example only):

```bash
sbt --server -no-server 'repl/run --provider=chatgpt --model=gpt-5.4 --ui=line'
```

`--provider=openai-codex` is an alias for `--provider=chatgpt`. Both `--model <id>` and `--model=<id>` work. The default is `gpt-6.1-sol`, as selected for this project; availability is **not** guaranteed for every account. Override it if the service rejects it. Model discovery is not implemented.

The subscription generation path does not require `MY_MODEL_URL` or `MY_MODEL_NAME`. It supports text, native tool proposals/results and encrypted reasoning continuation. `AgentRunner`, not the provider, executes advertised tools. Subscription/authentication failures never silently fall back to an API-key provider. Each model request has a two-minute timeout; SSE is currently buffered until the turn completes rather than displayed token by token.

Omitting `--provider` preserves the existing OAI endpoint mode and its configuration requirements. Audio/voice transcription remains an **independent OAI endpoint**, configured with `MY_MODEL_URL` and `MY_MODEL_NAME`; it is not included in this subscription adapter. Without valid OAI configuration, text chat still starts and a voice attempt reports a local configuration error. TTS and other platform API products are not covered by this provider.

## Credential storage and privacy

OAuth credentials are saved to the project-root gitignored `.env` using these existing keys:

- `OPENAI_CODEX_ACCESS_TOKEN`
- `OPENAI_CODEX_REFRESH_TOKEN`
- `OPENAI_CODEX_TOKEN_EXPIRES_AT` (Unix milliseconds)

Writes are atomic where supported, preserve unrelated entries and use owner-only file permissions on POSIX systems. Credentials are refreshed automatically before expiry and refresh is serialized within one client instance. **Do not run multiple processes sharing this credential file:** cross-process refresh locking is not implemented and refresh tokens can rotate. Do not copy tokens from Pi/Codex auth files; this login is independent. For legacy installations, the existing loader can migrate credential entries from `server/.env` when the main file cannot be loaded.

Keep `.env` private and out of version control/backups shared with others. To remove this project's login, delete only the three credential entries listed above; that does not revoke the provider session or alter Pi's credentials. Use your OpenAI account controls to revoke access if needed.

Provider debug captures under `debug/` contain prompts and tool data, though encrypted continuation fields are redacted and OAuth headers are not captured. Follow the [REPL privacy guidance](RELP.md); do not publish captures or terminal recordings containing private content.

## Verification

Offline regression tests:

```bash
sbt 'core/testOnly *OpenAiCodexSuite *OpenAiSubscriptionAuthSuite'
sbt 'repl/testOnly *ReplSuite *ReplProviderSuite *SubscriptionLoginSuite *TerminalLoginInputSuite'
sbt ';model/testOnly *;core/testOnly *;repl/testOnly *'
```

A **user-run** live smoke test is still required:

1. Complete one of the login flows above.
2. In the same subscription REPL (using an entitled model), ask for a brief text reply.
3. Ask it to read `README.md` using an advertised read-only tool; verify the tool call and final reply.
4. Confirm subsequent turns work. If access is rejected, check subscription eligibility, model access and usage limits; no paid API fallback is attempted.

Implementation status and deferred work are tracked in [the feature plan](OPENAI_SUBSCRIPTION_PROVIDER_PLAN.md).
