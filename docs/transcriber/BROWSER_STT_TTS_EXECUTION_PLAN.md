# Browser STT/TTS execution plan

> Audio storage update: the discard/redaction and audio-wide hash-exception decisions below describe the historical implementation.
> They are superseded by [Attachments and blob storage](../orchestration/ATTACHMENTS_AND_BLOB_STORAGE_PLAN.md).
> Audio now uses `MessagePayload.AudioAttachment` references, separate content storage, and append-before-inference;
> historical results below are unchanged. Voice uses the explicit `transcriberAgent`, not the selected chat provider.
> Memory-only/discard statements below apply to browser capture before ingestion, not backend recordings retained with
> `--storage`. Current validation and the open failure-path finding are recorded in the blob-storage plan.

Design: [BROWSER_STT_TTS_PLAN.md](BROWSER_STT_TTS_PLAN.md).

## Lane board

Base: `c2b9650fc6ee2002fe9f8be6b0b20c89fa11f183`.
Main checkout: `/home/fmgp/workspace/yallmo` (also mounted at `/Users/fabio/workspace/yallmo`).
Scratch lane root: `/tmp/yallmo-browser-audio.YSq9mD`.

| Lane | Owner / cwd | Exclusive boundary | Gate / durable handoff |
| --- | --- | --- | --- |
| Research | read-only subagent / main | Browser permissions, browser-triggered TTS, PCM WAV compatibility, current ZIO HTTP and REPL seams; no source edits | Concise evidence and implementation advice, no speculative APIs |
| Server | backend subagent / scratch `server/` worktree | New `repl/.../browser/` bridge/recorder/server and matching tests; resource packaging in `build.sbt`; no terminal/page edits | Focused compile/tests + exact Scala/wire contract report + `server.patch` |
| Page | browser subagent / scratch `page/` worktree | `browser-STT-TTS/` only; consume backend contract without changing it | Node tests, JS syntax check, browser constraints + `page.patch` |
| Integration | integration subagent / main, after both handoffs | Apply component patches; existing terminal widgets/status/lifecycle, terminal tests, user docs; no broad redesign | REPL test suite, formatter, integrated smoke checks; report changes and limits |
| Review | fresh read-only subagent / main | Actual integrated diff, lifecycle/security/regressions/simplicity | Cited findings and merge verdict; parent disposes findings |

No concurrent writers share a checkout. Components are isolated even though page implementation follows the backend contract serially. Scratch reports and patch handoffs stay outside tracked project files. Component workers do not commit or spawn children. Worktree Git commands may need `git -c safe.directory=/Users/fabio/workspace/yallmo` because of the dual mount; do not alter global Git settings.

## Steps

- [x] Confirm webpage scope and `v` / `m` / `t` behavior with user.
- [x] Inspect existing voice, provider, terminal and build seams.
- [x] Create design and execution plans before implementation.
- [x] Research browser setup/TTS policy and the minimal compatible recording strategy (saved findings recovered despite agent tool-profile failure).
- [x] Implement and test scoped loopback server + authenticated polling + browser `AudioRecorder`; publish wire/Scala contracts.
- [x] Implement and test plain webpage, bounded WAV capture, permission setup, polling and browser TTS against that contract.
- [x] Apply component handoffs and integrate `m`/`t`, lazy lifecycle, current-session assistant text, dynamic transcriber and shared insertion semantics.
- [x] Update help/status and docs with setup and limitations.
- [x] Run focused and full REPL tests plus scalafmt; inspect formatting diff for unrelated changes.
- [x] Run fresh read-only review; address concrete in-scope findings and revalidate affected gates.
- [x] Record final validation evidence, remaining manual checks and implementation completion here.

## Stop/escalation conditions

Stop on subagent infrastructure failure and preserve its state before a same-protocol retry. Escalate product/architecture changes, unsupported WAV/provider assumptions, or browser-policy constraints that prevent the agreed interaction model. Do not silently add external dependencies, expose a network listener, transmit to a different provider, or replace JVM `v`.

## Validation record

- Baseline `sbt 'repl/test'` exited successfully, but sbt 2 selected incremental `testQuick` (zero REPL tests and 34 aggregate core tests). This is not full-suite evidence. Integration must force `sbt 'repl / Test / testOnly *'` (and applicable aggregate projects) when recording full coverage. `show repl / Test / definedTests` confirms 17 baseline REPL suites are present.
- Research completed and its saved findings were recovered at `/home/fmgp/.pi/agent/sessions/--Users-fabio-workspace-yallmo--/subagent-artifacts/outputs/278f84ba-7970-4141-900f-3b9a376df701/research/browser-audio.md`. The orchestration then failed because the researcher profile requested unavailable `web_search`, `fetch_content`, `get_search_content`, and `source_check` tools. Run `278f84ba-7970-4141-900f-3b9a376df701` is terminal/failed; no implementation child launched, and both component worktrees contain only copied plans. Resume downstream work through the same subagent protocol using builtin-tool-only workers and a fresh read-only delegate for review; do not rerun completed research or change global tool configuration.
- Supervisor supplied actual Maven zio-http 3.0.1 sources and official MDN markdown under `/tmp/yallmo-browser-audio.YSq9mD/dependency-sources/`. Backend owner must verify APIs against compiler/tests. Research flags default 100 KiB HTTP body cap, `.port()` replacing loopback binding, terminal key-only waiting, and browser-dependent speech activation/background execution.
- Real browser microphone permission and audible OS/browser speech may need a manual check; automated mocks must be labeled as mocks.

### Integrated implementation and automated gate

- Both component handoffs were `IMPLEMENTED`; verified and applied `server.patch` then `page.patch` with `git apply --check` before each apply. SHA-256: server `2ff752dda0184850afe8139348088160762d0609b936dc3a4e6285e05fd68c8d`, page `0e394a96b2c00357ec29c32f6961a3d233d7337335e5ed731f15fb25771223fe`. No backend/page redesign or new dependencies.
- Terminal integration adds lazy single-flight construction owned by the complete REPL scope, `m` through shared `VoiceInsert`, and asynchronous explicit `t` from current `ReplState`. Both microphone bindings resolve the selected transcriber after agent changes, including unavailable audio. Setup printing runs separately in that scope: JLine holds its lock during a recording widget, so capture must never wait for another thread's `printAbove`.
- Bounded 100 ms JLine control reads preserve JLine pushback/macros and return on recorder completion/error/deadline without another key. Space/Esc and successful insertion spacing remain shared. Supervisor explicitly approved fixing the pre-existing cursor drift: cancellation, failure and empty transcripts now restore the exact original NORMAL cursor for both bindings; success retains the existing logical +1 insertion point.
- Focused gate: `timeout 180 sbt 'repl / Test / testOnly *VoiceInsertSuite *TerminalBrowserAudioSuite *BrowserTerminalIntegrationSuite *BrowserAudio*Suite'`: **38 passed, 0 failed** (20 backend + 18 shared voice/terminal/lifecycle tests). Log: `/tmp/browser-integration-focused.log`. Initial integration test failures exposed JLine lock contention and two fixture assumptions; fixed before the passing gate.
- Forced full gate before formatting: `timeout 240 sbt 'repl / Test / testOnly *'`: **189 REPL + 149 aggregate core = 338 passed, 0 failed**, not incremental `testQuick`. Log: `/tmp/browser-integration-full-before-format.log`.
- Ran path-only scalafmt after validation, with aggregation disabled: 10 changed main Scala paths and 6 changed test paths; 7 integration-owned files reformatted, no backend or unrelated files changed. Exact command: `/tmp/browser-integration-scalafmt.command`; log: `/tmp/browser-integration-scalafmt.log`.
- Reran forced full gate and packaging after formatting: `timeout 240 sbt 'repl / Test / testOnly *' 'repl / Compile / packageBin'`: **189 REPL + 149 core = 338 passed, 0 failed**. Log: `/tmp/browser-integration-full-final.log`. Actual HTTP tests served the integrated classpath HTML, all seven supporting assets, MIME/CSP/no-CORS headers; an independent ZIP check verified all eight runtime assets in the actual REPL jar exactly match source.
- Final JavaScript gate: `timeout 60 node --unhandled-rejections=strict --test browser-STT-TTS/*.test.js`: **38 passed, 0 failed**; all `node --check` syntax checks passed. Log: `/tmp/browser-integration-node-final.log`. Browser/audio/DOM tests use labeled mocks; terminal integration uses a real JLine loop and HTTP server but synthetic audio/transcribers/browser acknowledgements, not devices/providers.
- `git -c safe.directory=/Users/fabio/workspace/yallmo diff --check` and `diff --cached --exit-code` pass. New implementation paths have intent-to-add entries only; no content is staged, no commits or global Git configuration changes. Approved plan files remain untracked, with only this checklist/evidence updated.
- Existing clean-base risk remains: `.gitignore` ignores `debug/`, and the main checkout's required `repl/.../debug/ReplDebug.scala` is present but untracked/ignored. Main-checkout validation needed no workaround, but a pristine clone lacks that already-imported source. Not changed by this integration lane.

### Independent review and disposition

Fresh independent review completed. Its only finding was a concrete P2 race: an expected stale-operation HTTP 409 arriving before the next poll disconnected the page after Esc or repeated `t`. Parent accepted and fixed this in `bridge-client.js`: discard only that operation, abort its work and clear samples/timers without replay; let subsequent polling supply authoritative idle/replacement state. Authentication/network/unexpected failures and polling failures still fail closed. Added reverse-order recording/speech start, upload/end, browser-error, no-replay and fail-closed regressions. No Scala/API/backend redesign was needed.

Post-fix validation: **42 Node tests passed**, all JS syntax checks and `git diff --check` passed. Forced full Scala gate and packaging rerun passed: **189 REPL + 149 core = 338 tests**; all eight runtime assets in the jar match current source bytes. Logs: `/tmp/yallmo-browser-audio.YSq9mD/final-node-tests.log` and `final-scala-tests.log`.

Targeted fresh follow-up review completed: **No issues found. Merge verdict: OK with notes.** Reviewer independently reran all 42 Node tests and a late-response identity regression (409, 403 and network failures from replaced operations cannot affect the new operation), and inspected the final full Scala logs. Report: `/home/fmgp/.pi/agent/sessions/--Users-fabio-workspace-yallmo--/subagent-artifacts/outputs/1fd3ef0e-d463-40a9-b589-1a8f5dcf47c5/review/browser-audio-followup.md`.

Implementation and automated review are complete. All implementation/review lanes are terminal; original research run failed only at tool-profile validation and its complete research artifact was recovered. No pending supervisor decision, new dependency, commit, or staged content. Isolated component worktrees and patch handoffs are retained under the scratch root for provenance; main checkout contains the integrated changes. Pre-existing ignored debug-source caveat is explicitly outside this feature's changes.

### Follow-up: fixed port 5180

User requested a fixed port after initial completion. Production initially bound only `127.0.0.1:5180`, with no alternate-port fallback on conflict (binding superseded by the all-interface update below). Setup/SSH docs and the design plan reflect this. Added a default-binding regression; test servers explicitly use ephemeral ports so parallel suites and a running REPL do not compete for 5180. Targeted 18 tests passed, path-only scalafmt ran on the four changed Scala files, then forced full validation passed: **190 REPL + 149 core = 339 tests**, zero failures. Final log: `/tmp/yallmo-browser-port-5180-final.log`.

### Follow-up: Docker host access

Diagnosed the user's host-side curl empty reply without stopping their running REPL: container detected, listener was `127.0.0.1:5180`, inside-container loopback returned HTTP 200, and the container network-interface address refused the connection. Initially added an opt-in bind-address setting, keeping loopback as default and port 5180. That setting was subsequently removed at user request; see the all-interface update below. Setup URLs and token/Host/Origin checks remain loopback; Docker must publish `-p 127.0.0.1:5180:5180`. Existing REPL/container processes were not restarted or reconfigured by the agent; user must restart with the environment setting.

Added configuration and real wildcard-listener HTTP/security regressions. Nine bridge tests passed, path-only scalafmt ran, and full forced validation passed: **192 REPL + 149 core = 341 tests**, zero failures. Logs: `/tmp/yallmo-browser-docker-tests.log`, `/tmp/yallmo-browser-docker-format.log`, `/tmp/yallmo-browser-docker-final.log`. No claim of host-to-container access after restart or actual browser/audio verification. Docker setup and curl/fragment troubleshooting are documented in `browser-STT-TTS/README.md#docker`.

### Follow-up: all-interface default, no environment setting

User confirmed wildcard binding works and explicitly requested it always. Production now always binds `0.0.0.0:5180`; removed environment lookup and configuration parsing. Browser setup URLs, authenticated API, and strict Host/Origin checks remain `127.0.0.1:5180`. Docker publishing should remain host-loopback-only; direct execution now listens on every IPv4 interface, so firewall protection is documented. Updated setup/design docs and retained real wildcard-listener security coverage. No user process was restarted.

Eight bridge tests passed, path-only scalafmt ran, then full forced validation passed: **191 REPL + 149 core = 340 tests**, zero failures. The obsolete configurable-binding test was removed. Logs: `/tmp/yallmo-browser-default-bind-tests.log`, `/tmp/yallmo-browser-default-bind-format.log`, `/tmp/yallmo-browser-default-bind-final.log`.

### Follow-up: show setup URL only while disconnected

Updated shared terminal browser acquisition to inspect bridge connection status before displaying its setup URL. Connected `t` and `m` actions no longer print the link; disconnected actions still do, including after disconnect/reconnect. Added a real HTTP regression with repeated acknowledged speech, cancelled recording, explicit disconnection and reconnection. Updated user docs. Twelve focused terminal/integration tests passed, path-only scalafmt ran, and full forced validation passed: **192 REPL + 149 core = 341 tests**, zero failures. Logs: `/tmp/yallmo-browser-setup-link-tests.log`, `/tmp/yallmo-browser-setup-link-format.log`, `/tmp/yallmo-browser-setup-link-final.log`. Running user REPL was not restarted.

### Follow-up: config-file browser audio port

Added top-level `Config.browserAudioPort`, default 5180, and startup validation for 1–65535. Newly initialized private JSON configs serialize `"browserAudioPort": 5180`; older files without it retain that default and are not rewritten. `ReplMain` passes the loaded port through terminal startup into lazy bridge creation. All-interface binding, authenticated setup URL, dynamic Host/Origin checks, no environment lookup, and no fallback to another occupied port remain unchanged. Provider catalog validation also checks the new setting. Existing private user config files were not edited.

Added coverage for default serialization, older files, explicit valid/invalid ports without rewriting, and actual custom-port HTTP serving/setup URLs. Focused config tests: 9 passed; focused bridge/startup/catalog tests: 37 passed. Path-only scalafmt ran on eight changed Scala files. Full forced validation after formatting: **193 REPL + 151 core = 344 tests**, zero failures. Logs: `/tmp/yallmo-browser-config-port-tests.log`, `/tmp/yallmo-browser-config-port-repl-tests.log`, `/tmp/yallmo-browser-config-port-format.log`, `/tmp/yallmo-browser-config-port-final.log`.

### Follow-up: lifecycle file logs and WARNING+ terminal output

Added safe lifecycle INFO logs for browser audio service start/stop (bind address/port), client connect/reconnect, explicit disconnect, heartbeat expiration and service-shutdown disconnect. Startup failures emit a warning without raw causes or credentials. Polls do not emit logs. Setup tokens/URLs, client IDs, audio and assistant speech text are omitted. Existing storage-root logging routes these to `<storage>/logs/repl.log`; no new durable logger is enabled without `--storage`.

Initially added an unconditional WARNING floor and retained all file levels. Both choices were superseded by the user's clarified defaults below. Both microphone and TTS widgets capture the configured REPL runtime instead of using `Runtime.default`, so lazily constructed HTTP/session fibers retain the file logger across the synchronous widget boundary.

Added a console/file-level regression and real JLine/HTTP/file integration coverage for startup from both `m` and `t`, connect/reconnect, explicit/timeout/shutdown disconnect, stop, heartbeat deduplication and absence of tokens/client IDs/spoken text. Focused tests: **31 passed**. Path-only scalafmt ran; full forced validation: **195 REPL + 151 core = 346 tests**, zero failures. Logs: `/tmp/yallmo-browser-lifecycle-tests.log`, `/tmp/yallmo-browser-lifecycle-format.log`, `/tmp/yallmo-browser-lifecycle-final.log`. No running user REPL was restarted.

### Follow-up: independent logging defaults, no terminal floor

Corrected the logging interpretation: terminal output defaults to WARNING (both initial REPL state and startup context), but has no hard minimum floor. `/log info`, `/log debug` and `/log trace` can lower only the terminal threshold; `/log off` suppresses terminal logs for turns. File logging independently defaults to INFO and above and ignores the terminal FiberRef threshold. File loggers accept an independent threshold parameter; `/log` never changes it. Updated help and docs to state terminal-only command semantics.

Replaced the hard-floor test with coverage for default terminal behavior and TRACE/DEBUG/INFO/WARNING/ERROR/OFF selections while files remain at INFO. Added an explicit independent file-threshold test. Eighteen focused REPL tests passed; path-only scalafmt ran on the five changed Scala files. Final full forced validation: **196 REPL + 151 core = 347 tests**, zero failures. Logs: `/tmp/yallmo-log-defaults-tests.log`, `/tmp/yallmo-log-defaults-format.log`, `/tmp/yallmo-log-defaults-final.log`.

### Remaining acceptance gates

Code implementation, automated validation and targeted independent review are complete; hardware/browser acceptance is not claimed. No Chromium/Chrome/Firefox executable was found in this lane. Parent's MCP Playwright attempt failed because `chrome-for-testing` is not installed at `/home/fmgp/.cache/ms-playwright/chromium-1247/chrome-linux-arm64/chrome`; no browser was installed and no real browser automation is claimed. Real permission/device capture, intelligible provider transcription, worklet/CSP execution in a browser, audible OS/browser TTS, background/revocation/re-enable behavior and optional SSH tunneling remain manual acceptance items in `browser-STT-TTS/README.md`. No real microphone, provider audio call or audible speech was tested.
