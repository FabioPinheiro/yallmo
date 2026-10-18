# Browser STT/TTS design plan

## Goal and agreed behavior

Use a local webpage, not a Chrome extension, as an alternative microphone and browser TTS frontend. Keep the REPL in charge. No frontend framework, package installation, extension manifest, external recorder, or server-side TTS.

- Preserve NORMAL `v`: existing JVM microphone voice insertion.
- Add NORMAL `m`: browser microphone voice insertion using the same selected transcription provider/model and draft-insertion behavior.
- `Space` stops browser recording; `Esc` cancels without transcription. Keep the existing 30-second maximum.
- Add NORMAL `t`: explicitly speak the latest assistant text in the current conversation through browser `speechSynthesis`. No automatic speech, user messages, tool results, or history submission.
- A small persistent page in `browser-STT-TTS/` is served by ZIO HTTP from the REPL process. Initial setup is a user click to grant microphone access and enable browser audio; keep the tab open afterward.
- Permission may persist, but browser/OS revocation or audio autoplay policies can require another click. Do not promise permanent permission or unrestricted background-tab execution.

## Architecture

1. A scoped ZIO HTTP server serves static HTML/JavaScript and an authenticated audio-control API. Bind `0.0.0.0` on the configuration’s top-level `browserAudioPort` (default 5180) for Docker compatibility, without a bind-address environment variable. Publish the configured Docker port only on the host's loopback; protect the all-interface listener with firewall rules when running directly. Browser URLs and Host/Origin checks remain loopback. Reuse the existing zio-http 3.0.1 dependency.
2. Use a random per-process token in a URL fragment and authenticated API headers. No wildcard CORS. Do not log audio, assistant text, credentials, or API headers. Serve page assets without a token; only authenticated calls may control or retrieve audio operations.
3. Use simple short HTTP polling for REPL-to-browser commands, operation IDs, bounded state, a single active page, heartbeat/disconnect detection, and deadlines. Define the exact wire contract in the backend handoff before implementing the page.
4. Expose a browser-backed `AudioRecorder` implementation so both microphones share the existing `Transcriber` and line-editor-only insertion flow. The recorder must race stop/cancel with browser failures and deadlines, and clean up on interruption.
5. Browser capture must produce provider-compatible WAV: the current model supports only `AudioFormat.Wav`, and providers cap audio at 1 MiB. Prefer bounded Web Audio PCM capture/resampling to 16 kHz, mono, 16-bit WAV; do not send MediaRecorder WebM as WAV or require ffmpeg.
6. Reuse voice insertion spacing, saved-cursor behavior, NORMAL restoration, error handling, and dynamic selected-agent transcription. Neither browser input nor TTS is an agent tool.
7. Select the latest assistant text from the current conversation state, including resumed sessions; clear/change the target when sessions change. A missing response or disconnected page produces a local message, not an agent request.
8. TTS sends only explicitly requested assistant text. The browser replaces prior speech, reports speech errors, and uses browser-selected voices. Explain that speech engines may use network services; local synthesis is not guaranteed.
9. The server should start lazily on first browser action, print the setup URL, remain available for browser audio, and close with the REPL. A startup failure must not break JVM voice or ordinary text input. Use `browserAudioPort` from the config on all IPv4 interfaces and display its authenticated `127.0.0.1` URL with that port. New config files include `"browserAudioPort": 5180`; missing fields in older files default to 5180. Validate 1–65535, reject invalid values without rewriting, and do not switch ports if occupied.
10. Preserve privacy: audio exists in memory only, goes to the existing transcription provider only after stop, and is discarded on cancellation/failure. Bound recording length, upload bytes, pending commands, text size, and request wait times.

## User flow

- Press `m` (or `t`) to initialize the bridge and receive the local setup URL if not connected.
- Open the URL; click Enable audio and accept microphone permission. Test/unlock speech within that gesture when needed.
- Return to the REPL. Press `m`, speak, then `Space` to insert the transcript into the saved draft; use `Esc` to discard instead. Press `i` to edit and Enter only when ready to submit.
- Press `t` to speak the last assistant response once. Keep the audio page open. Show clear setup/connected/recording/speaking/error state on the page.

## Non-goals

Tab/system audio capture; browser speech recognition; remote network exposure; multi-client coordination beyond rejecting a second active tab; automatic submission; automatic TTS; persistent recordings; extension packaging; frontend build tooling.

## Validation and acceptance

- Existing REPL tests and JVM `v` behavior remain intact.
- Tests cover authentication/origin and client boundaries, stop/cancel/failure/timeout cleanup, stale/duplicate uploads and operation IDs, WAV/size limits, unavailable browser, and server scope shutdown.
- Terminal tests cover `m`/`t` bindings, draft/cursor preservation, no auto-submit, NORMAL restoration, active transcription provider, and current-session assistant selection.
- JavaScript tests cover WAV encoding/resampling and bounded recording behavior using Node built-ins where feasible. Use browser automation for serving/setup/error smoke tests; do not claim real microphone/provider or OS speech playback verification without actual evidence.
- Document setup, security/privacy, browser restrictions, and optional SSH loopback port forwarding without expanding the supported default beyond same-machine use.

## Implementation topology

Multi-seam: backend bridge/recorder and browser page are independently testable contracts. Use separate isolated worktrees, one owner per component, then a dedicated integration owner for existing terminal wiring and docs. Parent retains design decisions, review disposition, and final acceptance. See `BROWSER_STT_TTS_EXECUTION_PLAN.md` for ownership and gates.
