# Browser audio page

Plain same-origin HTML/ES modules/CSS; no installation, build, framework, external assets or encoder. The REPL bridge serves these files from its classpath. Opening `index.html` as a file or using a separate web server does **not** connect to the REPL.

## Setup

Configure the REPL's explicit `transcriberAgent` first: an audio-capable agent with a nonblank model/prompt and empty
`tools`, `retrievers`, `skills` and `extensions` allowlists. See [the configuration example](../docs/examples/repl-config.json).
Missing references disable `m`/`v` before capture; invalid explicit configuration fails startup locally. Chat selection
(including no selection) and chat-agent switches do not change the voice agent. Local adapter checks do not prove remote
endpoint/model audio compatibility.

1. Use the REPL's browser audio action (`m`/`t`) and open its exact `http://127.0.0.1:5180/#token=…` URL. The REPL prints this setup link only when the page is disconnected, not on every connected audio action.
2. Click **Enable audio** in a focused desktop browser supporting AudioWorklet, Web Audio, microphone access and `speechSynthesis`. Accept microphone permission. The click resumes Web Audio and requests an “Audio enabled.” speech test before awaiting permission. Setup is bounded to 20 seconds (speech test: 4.5 seconds). No claim of audible output is made from enqueueing or even browser events.
3. Keep the tab open. Back in the REPL, `m` starts capture, Space stops/transcribes, Esc discards; `t` speaks explicitly selected assistant text once. Nothing records or speaks automatically, apart from the user-clicked setup test. The microphone stream stays open/indicated while enabled, but capture buffers/processing exist only during a record command.
4. **Disable audio**, page exit, freezing, connection loss, ended tracks or a suspended/closed audio context stop tracks and speech. Reopen the original setup URL after reload: its token fragment is immediately removed from browser history/address bar and kept only in memory. Failed operations never replay. Re-enable creates a fresh client/operation context; if the previous disconnect is uncertain, wait five seconds before retrying.

The server binds to `0.0.0.0` on the config file’s top-level `browserAudioPort` (default `5180`) for Docker compatibility; if that port is occupied, startup fails locally without switching ports. Newly created config files include `"browserAudioPort": 5180`; older files without it use the same default without being rewritten. Valid ports are 1–65535. Edit the config and restart the REPL to change it; the browser uses the printed `127.0.0.1` URL with that port. The listener accepts connections on all IPv4 interfaces: protect it with host firewall rules and publish Docker only on host loopback. For an optional SSH tunnel, preserve the *same port and canonical host* on both sides, e.g. `ssh -N -L 127.0.0.1:5180:127.0.0.1:5180 user@host`. Open the unchanged setup URL locally. An occupied local port, `localhost`, or a different forwarded port will not satisfy the bridge's exact Host/Origin checks. This is not remote network exposure/support.

## Docker

The server binds all IPv4 interfaces automatically; no bind-address environment variable is used. Publish the configured port on the **host's loopback only** (default 5180):

```sh
docker run -p 127.0.0.1:5180:5180 <image>
```

For an existing container with that port mapping, just restart the REPL after updating. The browser still opens the printed `http://127.0.0.1:5180/#token=…` URL, never `0.0.0.0` or a container IP. If `browserAudioPort` is changed, replace 5180 in the Docker mapping and browser URL with that value. Keep the same host/container port for Host/Origin checks. Do not publish on a public host interface.

Test page access from the host with `curl -i http://127.0.0.1:5180/`. A URL fragment (`#token=…`) is browser-side and is not sent by curl or in HTTP requests; it is not required to fetch the static page. Browser setup uses it to authenticate subsequent API calls. An empty reply while in-container loopback works usually indicates the listener or Docker port mapping is wrong.

## Behavior and limits

- Native sample rate is read from AudioWorklet's `sampleRate`; channel samples are averaged, windowed-sinc low-pass filtered and statefully resampled to 16 kHz. Fractional phase/history survive arbitrary render-block boundaries. No MediaRecorder/WebM, base64, external encoder or filesystem storage.
- The worklet itself caps native frames and output at the requested duration (1–30 seconds), at most 480,000 mono PCM16 samples. One bounded PCM buffer is transferred on completion; canonical 44-byte-header WAV is at most **960,044 bytes**. Muted output prevents mic feedback. Main-thread and backend deadlines also fail closed.
- Sequential 1-second polling, one poll in flight, 4-second request timeout; all API credentials are headers, never query parameters. IDs deduplicate repeated starts. Missing/replaced snapshots abort old callbacks, capture and uploads. Final WAV/speech end waits for successful *actual start* acknowledgement. A stale-operation 409 after cancellation/replacement discards only that operation and awaits the next authoritative poll, without replay. Authentication, connection and uncertain-response failures disable audio instead of retrying.
- Speech uses a retained plain-text utterance and real `onstart`/`onend`/`onerror` events; start and completion watchdogs are 4.5 and 120 seconds. Replacement cancels old speech. Browser voices may use **network services**; local synthesis and audible output cannot be guaranteed.
- In this browser page, audio and text live in memory only. No storage APIs, telemetry, external requests, DOM HTML insertion or credential/error logging. Stopped audio is sent to the REPL and then its configured transcription agent; cancellation discards it. Keep the setup URL private.
- The backend retains each successful transcription independently of draft submission/discard in a generated
  `transcription-<uuid>` session: human audio root with empty bytes, original encoded size and sample-count duration,
  then an ordinary assistant transcript attributed to the configured agent. Raw recordings remain live-operation-only.
  Transcripts/descriptions can be sensitive. Active chat context/head/continuation and ordinary resume are unchanged.
  Every voice request is one fresh turn containing only current audio and configured instructions; no tools, RAG, skills,
  extensions, previous exchange or follow-up execution. Persistence failure leaves the draft unchanged and is reported.
- Permissions, activation and background execution vary by browser/OS. A tab may be throttled/frozen or speech rejected/stalled despite setup succeeding. Focus the page and Enable again; this implementation intentionally has no automatic retry, recorder fallback or permanent-permission promise.

## Lifecycle logs

When the REPL uses `--storage <directory>`, `<directory>/logs/repl.log` records audio service start/stop and port, connect/reconnect,
explicit disconnect, heartbeat timeout and shutdown disconnect. Terminal log output defaults to WARNING; `/log info` (or lower)
can show INFO events there. File logs independently default to INFO and above, unaffected by `/log`, even `/log off`. Startup failures produce a warning. These messages omit setup tokens, client IDs, audio and spoken text;
ordinary polling is not logged.

## Automated checks

From the repository root (Node built-ins only):

```sh
node --test browser-STT-TTS/*.test.js
for file in browser-STT-TTS/*.js; do node --check "$file" || exit; done
```

Tests cover canonical WAV/bounds/clipping, native rates including 44.1/48 kHz, block invariance, stereo downmix and anti-alias suppression; execute the worklet with a mocked processor/port; test operation/acknowledgement/upload/cancellation/replacement/deadline races; test header-only transport, non-JSON errors, timeouts and aborts; and execute the page with mocked DOM/audio APIs to verify gesture order, permission denial/timeout/late completion and shutdown. These are **mocks, not real browser/microphone/OS playback evidence**.

## Manual acceptance (not performed by Node tests)

- Through the actual REPL server, verify page/modules/styles/worklet load under its CSP; no CORS/console errors, external requests, or token in request URLs; token disappears from address bar. Reload without the original fragment must require reopening the URL. A second active tab must be rejected without evicting the first.
- Enable: grant/deny/ignore permission, then Disable while permission is pending and grant it late. Late tracks must stop and no connection/recording may appear. Confirm the explicit speech test is actually audible; lack of audibility is not contradicted by a browser event.
- Record intelligible speech on 44.1/48 kHz devices: Space produces a valid transcript in the saved draft, never submission; Esc before/after actual start discards without upload/transcription. Run to 30 seconds; confirm one bounded WAV, no feedback, responsive terminal, and no restart on repeated polls. Check quick stop, device unplug, permission revocation and context suspension.
- Speak requested assistant text only; check real audible output, completion and replacement. Deny/stall speech or move to a background tab: errors/timeouts must return to a recoverable disabled state, not report enqueue as completion. OS/network voice behavior is browser-dependent.
- During start acknowledgement and upload, cancel/close/kill the server, go offline, sleep or freeze the tab. Tracks/speech must stop on reconciliation/failure; no late/cancelled upload can become a transcript. Re-enable must not replay any prior operation. Pagehide disconnect is best effort; the backend heartbeat is authoritative when a browser cannot deliver it.
