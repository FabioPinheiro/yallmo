# Transcriber as a configured audio agent

Status: implemented; automated validation and independent review passed. Manual microphone/browser and remote endpoint/model audio checks remain outstanding. Results and residual risks are recorded in [TRANSCRIBER_AS_AN_AGENT_EXECUTION_PLAN.md](TRANSCRIBER_AS_AN_AGENT_EXECUTION_PLAN.md).

## Goal

Replace the dedicated core `provider.Transcriber` service with a configured, audio-capable agent. Reuse and improve the model/core turn APIs so audio is a real message payload rather than a REPL-only side channel. The REPL-local `repl.voice.Transcriber` service invokes that ordinary agent and handles transcript validation/retention; it is not a core service or a new agent type.

Both JVM and browser voice input must use the configured transcription agent, independently of the selected conversational agent. Every transcription starts with fresh context.

## Agreed decisions

- Add an optional configuration reference named `transcriberAgent`.
- Transcription is available only when this reference is explicitly set. No hard-coded agent lookup and no fallback to the selected chat agent/provider/model.
- Add an example agent whose ID is `Transcriber`, with its model and system prompt in configuration. The name is an example catalog entry, not a special runtime identity or a new provider type.
- The referenced agent must support audio and use an audio-capable provider (`LlmProviderAudio`). Adapter capability does not guarantee that a particular endpoint/model accepts audio.
- Reuse `AgentTurnRequest` and `AgentTurnResponse` through the existing `turn(request): response` contract; do not introduce a separate transcription request/response API.
- Add an audio-capable agent trait using the existing `turn` request/response contract.
- Make `AudioInput` itself a `MessagePayload`; do not introduce `MessagePayload.Audio(AudioInput(...))` double wrapping.
- Remove `AudioInput.validate`. Request/provider boundaries remain responsible for rejecting audio that cannot be sent.
- Persist audio messages and their metadata, but always write empty audio bytes in persistence and debug output.
- Add original size, duration in seconds, and an optional description to audio metadata, using the proposed model below.
- Retain original audio bytes in memory only for the live operation, then release them. Do not retain recordings in session history or write recordings/base64 audio to disk.
- Skip hash verification only for audio messages, whose persisted bytes are intentionally redacted. Preserve hash verification for all other message types, including transcription results stored as assistant messages.
- Delete the existing `Transcriber` class and its hard-coded prompt.
- Transcription must not execute tools, RAG, skills, context extensions, or conversational follow-up loops.
- Keep editable voice behavior: insert the transcript into the local draft; do not automatically submit it.
- Store and retain transcription results normally as `MessagePayload.Assistant`, attributed to the transcription agent. Discard the raw audio bytes, not the assistant message. Retention is not conditional on submitting or keeping the editable draft.
- Missing capability settings preserve today's setup for those capabilities. Explicit allowlists restrict the corresponding capabilities; empty allowlists disable them.
- Missing `transcriberAgent` disables voice while leaving text chat usable. An explicitly configured but invalid transcription agent fails startup with an actionable error.
- Audio size, duration, and description are local persistence/debug metadata, not model input. Format remains necessary for audio wire encoding.
- The transcription agent is an ordinary agent and remains selectable in the normal agent menu.
- Retain each transcription exchange in its own session, separate from the active chat. Generate a collision-resistant session ID automatically; no user-supplied name is required.
- Defer message-hash lookup in `/debug` to [DEBUG_BY_MESSAGE_ID_PLAN.md](DEBUG_BY_MESSAGE_ID_PLAN.md). This implementation does not add transcription records to the active chat's debug/inference context merely to expose them in the UI.
- The user authorizes updating the local `yallmo.json` as well as tracked examples. Preserve existing agents, selection, provider endpoints, and credentials; never print or commit secrets.

## Current implementation findings

### Model

- `model/src/main/scala/fmgp/yallmo/model/Agent.scala` defines `Agent[-Environment]` with `turn(AgentTurnRequest)`.
- `model/src/main/scala/fmgp/yallmo/model/llm/AgentTurn.scala` carries ordered `MessagePayload` input. Validation requires a nonblank model and final `Instructions` item.
- `model/src/main/scala/fmgp/yallmo/model/Message.scala` defines a sealed payload hierarchy with JSON codecs, role, and `contentStr`.
- `model/src/main/scala/fmgp/yallmo/model/llm/AudioInput.scala` currently contains bytes, WAV format, and `validate`.
- `LlmProviderAudio` currently accepts a prompt, one audio input, and an optional model, returning only text. It is separate from the native agent turn path.

### Core

- `core/src/main/scala/fmgp/yallmo/provider/Transcriber.scala` owns the hard-coded transcription prompt.
- `OAILlmProvider` implements audio inference. Existing protections include a nonempty payload check, a 1 MiB limit, and a request timeout.
- `OAIChatCompletionsMapper.audioRequestBody` builds a dedicated system-plus-audio Chat Completions request.
- `OAIAgent` currently handles native text/tool turns. Its debug path writes provider payloads through `LlmProviderPayloadDebug`.
- `RuntimeInstances` lazily resolves and caches agents/providers. Configuration loading and lookup must remain free of inference/network calls.
- `AgentConfig` has no typed capability policy. REPL startup currently supplies tools through `StaticContext`.
- `StaticContext` includes tools, retrievers, skills, and extensions. `AgentRunner` checks advertised capabilities before executing actions.

### REPL

- `ReplStartup` builds transcription from the selected chat agent's provider/model.
- `ReplApplication`, `ReplMain`, `TerminalRepl`, and `VoiceInsert` pass the concrete `Transcriber` service around.
- JVM and browser recording share the voice insertion workflow.
- `/debug` currently reflects conversational run context; a separate transcription invocation will not appear there automatically.

### Storage and diagnostics

- `objects.jsonl` stores serialized `MessageData` envelopes.
- `MessageId.from` hashes serialized message data.
- `StorageStore.load` currently rejects hash mismatches. Accepting redacted audio requires changing this behavior, not merely redacting the JSON writer.
- `StorageStore` also checks conflicting duplicate IDs and missing parents. Those checks are distinct from hash verification.
- Provider debug capture operates on already-built wire JSON. Redacting the model serializer alone will not redact base64 `input_audio.data` there.

## Intended design

### Audio payload

Planned model shape:

```scala
final case class AudioInput(
    bytes: Chunk[Byte],
    format: AudioFormat,
    sizeBytes: Long,
    durationSeconds: Option[Double],
    description: Option[String] = None
) extends MessagePayload
```

- Audio has user-role semantics.
- `contentStr` and terminal rendering show descriptive metadata, never raw bytes/base64.
- Size, duration, and description are local metadata only. Do not send them as model text or instructions. The provider still receives the format required to decode the audio.
- Size records the original encoded bytes, not the redacted array length.
- Capture paths populate duration from recording/sample information when available; do not add an external decoder dependency just to determine duration.
- Empty bytes are valid for a persisted metadata-only object, but not for a live audio inference request.
- Because `MessagePayload` is sealed, its direct subtype must be defined in the same source file. Preserve a convenient `AudioInput` import where practical.
- Update JSON support for the payload and format, and review existing stored-message compatibility.

### Audio-capable agents and providers

- Add an explicit audio-capability trait extending the agent contract, retaining `turn(AgentTurnRequest)`.
- Provide a checked runtime lookup for the configured transcription agent.
- Keep `LlmProviderAudio` limited to prompt-based audio inference; do not add `turn` to that provider capability. Structured request/response semantics and implementation belong to `Agent`/`AudioAgent`. `OAIAgent` owns request validation, turn mapping, and turn diagnostics; `OAILlmProvider` has no turn or `completeTurn` method and supplies only prompt inference and lower-level wire completion. Share audio wire encoding/validation/transport primitives, not agent turn handling.
- Keep provider-specific encoding and constraints in core/provider adapters.
- Support audio payloads in the OAI mapper and reject audio explicitly in unsupported native adapters. Never silently drop it or replace it with `contentStr`.
- The legacy `LlmDecisionAgent` text-prompt renderer is an explicit exception: render audio as `SYSTEM ERROR: decision agents do not support audio input` and log that error, without throwing/failing or rendering audio bytes/metadata. `renderTask` and `renderPrompt` are effects; inference and decision decoding continue with the marker in history.
- Preserve explicit model selection, stop reasons, and response metadata where supported.
- Do not perform tools or orchestration inside the provider adapter.

### Configuration

```json
{
  "transcriberAgent": "Transcriber",
  "agents": {
    "Transcriber": {
      "agentType": "OAIAgent",
      "providerIds": ["audio-provider"],
      "capabilities": {
        "tools": [],
        "retrievers": [],
        "skills": [],
        "extensions": []
      },
      "config": {
        "model": "audio-capable-model",
        "systemPrompt": "Transcribe the supplied audio exactly. Preserve the spoken language. Do not translate, summarize, answer, or call tools. Return only the transcript."
      }
    }
  }
}
```

This is a proposed schema fragment; a real configuration must also contain the normal required fields and the referenced provider definition.

- Configuration model field: `transcriberAgent: Option[AgentId] = None`.
- Existing files without the field remain usable, with transcription disabled.
- Fresh empty configurations remain valid and do not invent a provider/model.
- Validate configured references and audio capability without contacting the endpoint.
- Reuse/extract model and prompt settings decoding into core where needed; avoid making core depend on `ReplAgentSettings`.
- A configured transcription prompt must be explicit and nonblank, not silently replaced by the generic conversational prompt.
- Resolve and validate an explicitly configured transcription agent during REPL startup. Report the agent ID and relevant invalid/missing field or provider capability without leaking credentials.
- Keep voice-specific setup/validation in `ReplStartup`, not core runtime. Resolve the ordinary audio agent through generic `RuntimeInstances.getAudioAgent` and pass that agent plus generic settings directly to the REPL voice workflow. Do not introduce a `ConfiguredTranscriber` wrapper or a `RuntimeInstances.configuredTranscriber` method; core retains only generic lookup/settings/capability APIs.
- These startup checks are local configuration/adapter checks, not an inference or endpoint health probe. Remote inference failures remain invocation-time errors.
- Include the transcription agent in the normal agent catalog and selection menu.
- Update local `yallmo.json` with an explicit transcription agent, reference, nonblank prompt/model, and four empty capability allowlists. Reuse an existing OAI provider/model as an explicit initial configuration where suitable, not as a runtime fallback; do not invent credentials or endpoints. Audio support of that endpoint/model remains unverified until a manual invocation succeeds. Report this limitation rather than claiming local adapter validation proves remote support.
- Make only targeted local configuration changes and preserve unrelated fields and credentials. Do not copy local credentials into tracked examples or logs.

### Capability policy

Introduce typed per-agent allowlists rather than relying on “do not call tools” in a prompt.

Capability semantics:

- Omitted capability settings inherit today's setup. Represent omission separately from an explicitly empty list.
- Empty allowlist means no capability, not “inherit all available capabilities.”
- Filter tools/retrievers/skills/extensions when constructing the agent's context.
- Enforce authorization at the action boundary as well as when advertising capabilities.
- Do not allow caller-supplied tools in an assembled request to bypass the configured policy.
- Reject unknown capability IDs with a clear configuration error.
- Missing capability configuration preserves existing application setup and behavior; do not require migration for existing conversational agents.
- An explicitly configured allowlist overrides/restricts the corresponding capability; omitted fields preserve their existing setup. Empty lists mean disabled.
- For the configured voice transcription agent, require the effective tools/retrievers/skills/extensions to be empty after resolving configuration; reject a conflicting configuration at startup rather than silently enabling capabilities. The example explicitly disables all four.
- For each voice transcription request, send no tool definitions, use `ToolChoice.None`, and disable parallel tool calls.
- Reject an unexpected tool-call response from voice transcription without executing it or starting another turn.
- Keep the agent and audio interfaces general-purpose. Voice-specific fresh-context/single-turn restrictions belong to the voice invocation path, not every use of an audio-capable agent.

### One transcription invocation

1. Resolve the explicitly configured audio agent and its model/prompt settings.
2. Obtain audio from the existing JVM/browser recorder.
3. Build a fresh request containing the current `AudioInput` followed by configured `Instructions`.
4. Include no previous messages, provider continuation, tools, RAG, skills, or extensions.
5. Execute exactly one agent turn, outside the conversational `AgentRunner` loop.
6. Extract a nonblank text transcript; fail cleanly on unsupported or unexpected responses.
7. Insert text into the existing draft, preserving current cancellation/error/cursor behavior.
8. Retain the exchange in its own automatically identified session: human-authored metadata-only audio followed by a normal `MessagePayload.Assistant` attributed to the transcription agent, whose parent is that audio message. Preserve the audio message's original identity while retaining only empty bytes and original metadata; do not retain the raw bytes beyond the live operation. Successful draft insertion must not bypass retaining the result; a persistence failure must be reported explicitly.

Persisting or displaying an invocation does not make it context for the next transcription. Saving the assistant result is distinct from submitting the editable draft as a user message to the selected chat agent; it must not implicitly send the stored transcription exchange to that chat agent.

### Session organization and diagnostic scope

- Audio and assistant message artifacts are intrinsically addressed by their message hashes, not by session names. However, the current application-facing `Orchestrator` persistence API only appends through a session. Reuse that supported API rather than expanding this migration into sessionless storage APIs.
- Create one session per retained transcription exchange with a safe, collision-resistant generated ID such as `transcription-<uuid>`. The current model uses the ID as its session name/path segment; it has no separate required display name. Do not prompt the user for a name.
- The audio starts this separate thread with no chat parent; the assistant transcript references the audio. Register the human and transcription-agent authors using normal orchestrator checks.
- Do not change the active chat's session ID, head, run context, continuation, or selected agent. Ordinary chat resume must not import these separate exchanges. Each later voice request still has fresh context even though earlier exchanges were persisted.
- Keep normal session listing semantics; these generated sessions may appear there. Resuming a transcription session is not the intended diagnostic workflow and must not be done implicitly just to inspect it. Restored audio remains non-replayable.
- Do not add a new `/debug` transcription section or hash selector in this implementation. Existing debug rendering/export and provider request capture must still redact any audio they encounter. Future read-only hash inspection is specified separately in [DEBUG_BY_MESSAGE_ID_PLAN.md](DEBUG_BY_MESSAGE_ID_PLAN.md).
- Sessionless artifact persistence can be considered separately if useful; it is not required for transcription or for the future hash lookup, which should read already-addressed artifacts directly.

### Redaction, storage, and hash behavior

| Boundary | Representation |
| --- | --- |
| Live audio object | Original bytes and metadata |
| Provider HTTP request | Base64 original bytes and required format; no size/duration/description |
| `objects.jsonl` | Empty byte array, original metadata |
| Existing debug rendering/export, when audio is encountered | Empty byte array or metadata-only rendering; no new transcription lookup UI in this migration |
| Provider debug request JSON | Empty/redacted audio data, never original base64 |
| Logs, errors, `toString` | Safe metadata, never audio bytes/base64 |

- Centralize the redacted message projection/encoding so persistence and debug output agree.
- Redaction must not mutate the live audio input before the HTTP request is built.
- Keep hashing and redacted serialization conceptually separate. Do not require rehashing the redacted object to reproduce the original ID.
- Bypass hash verification only for audio records so reopening storage and later appends succeed. Keep the hash check unchanged for non-audio records, including the transcription assistant result.
- Preserve existing IDs and parent references; do not rewrite historical IDs as a side effect of redaction.
- Do not remove structural JSON/reference checks merely because hash verification is unnecessary.
- Review duplicate-ID handling: a live object and its redacted stored form must not be treated as conflicting solely because bytes were removed.
- A restored audio message is metadata-only and cannot be replayed. Provider validation must report this clearly.
- Confirmed privacy policy: discard raw audio bytes after the live operation; retain audio metadata and the ordinary assistant transcription message. The assistant message is not discarded or redacted when the editable draft is discarded. Document that transcripts and descriptions may contain sensitive text.

## Implementation sequence

### 1. Model and serialization

- Add the direct audio payload, metadata, codecs, role, and safe rendering.
- Remove `AudioInput.validate`; move live-send checks to the request/provider boundary.
- Update payload pattern matches and unsupported-modality errors across adapters, prompt builders, and renderers.
- Establish the safe persistence/debug projection without accidentally destroying live request bytes.

### 2. Core audio turns

- Add the audio-capable agent trait and checked runtime lookup.
- Implement OAI audio support through the shared turn model.
- Improve/adapt the provider audio interface and request mapper.
- Retain byte limits, timeouts, explicit model selection, and tool-free behavior for voice transcription; keep the shared turn interfaces general-purpose.
- Add wire-JSON audio redaction before any debug file is written.

### 3. Configuration and capability enforcement

- Add optional `transcriberAgent` decoding/reference checks and fail startup with actionable information if an explicitly configured reference is invalid.
- Extract reusable configured model/prompt settings into core.
- Implement the agreed capability policy, context filtering, and action enforcement. Preserve today's setup when capability configuration is absent.
- Reject a transcription agent with unsupported audio or prohibited capabilities.
- Update the example catalog and authorized local configuration with the `Transcriber` agent and explicit prompt/model; preserve all unrelated configuration and credentials.

### 4. Storage and diagnostic integration

- Persist audio with empty bytes and retained metadata.
- Skip hash verification for audio records only; preserve verification for all non-audio records.
- Ensure store reopen, later append, duplicate handling, parent traversal, and mixed text/audio histories work.
- Persist each transcription exchange in a separate automatically identified session with correct human/audio and transcription-agent/assistant attribution and parent linkage. Leave the active chat session/head/context unchanged.
- Retain ordinary assistant results independently of draft submission/discard, without adding old recordings/results to later transcription requests or resumed chat context.
- Ensure existing diagnostic renderers and provider capture are audio-safe. Hash-based `/debug` lookup is future work only; see [DEBUG_BY_MESSAGE_ID_PLAN.md](DEBUG_BY_MESSAGE_ID_PLAN.md).

### 5. REPL migration and cleanup

- Route both voice inputs through the configured transcription agent.
- Remove transcription dependency on selected chat agent and agent-switch callbacks.
- Remove the concrete `Transcriber` dependency from startup/application/terminal APIs and tests.
- Delete `core/src/main/scala/fmgp/yallmo/provider/Transcriber.scala` and hard-coded transcription policy.
- Keep text-only startup and draft editing usable when transcription is disabled.
- Update `docs/REPL.md`, relevant browser-audio documentation, and configuration examples.

### 6. Verification

- Run narrow model/core/REPL suites first, then the full `sbt test` suite.
- Run `sbt scalafmtAll` after validation and inspect for unrelated formatter changes.
- Manual checks: JVM recording, browser recording, cancellation, consecutive transcriptions, agent switching, disabled transcription, and storage/debug inspection.

## Acceptance criteria

- Missing `transcriberAgent` disables transcription with a clear local message and no provider fallback; text chat remains usable.
- Invalid explicit transcription configuration fails startup with actionable, secret-safe information.
- Capability settings omitted from existing agents preserve their current behavior; explicitly empty lists disable the corresponding capabilities, including in partially specified capability objects.
- The transcription agent appears in the ordinary agent selection menu.
- Voice works with its configured audio agent even when the selected chat agent is text-only or no chat agent is selected.
- Switching chat agents does not change the transcription model/provider/prompt.
- Two consecutive transcriptions each send only their own audio and configured instructions.
- The audio is a direct `MessagePayload` and has no `validate` method.
- Live provider requests contain original audio; persisted/debug representations do not. Session history retains the metadata-only audio message, not the original bytes.
- Size, available duration, format, and optional description survive redaction and reload. Size/duration/description are not sent to the model.
- Metadata-only restored audio is rejected for live inference, not silently dropped.
- Redacted audio hash mismatches do not prevent storage reopening or subsequent appends; non-audio hash mismatches are still rejected.
- Transcription results are normal persisted assistant messages attributed to the transcription agent, independently of draft submission or discard, in their own automatically identified sessions. No user-supplied session name is needed.
- Retaining a transcription leaves the active chat session/head/context unchanged; consecutive voice requests and ordinary chat resume do not import transcription exchanges.
- Existing diagnostic paths remain audio-safe; inspecting arbitrary message hashes through `/debug` is explicitly deferred.
- Local configuration contains the explicit transcriber reference/settings without modifying existing chat selection, credentials, or unrelated fields; remote model audio support is documented as unverified unless actually tested.
- Prohibited tools/RAG/skills/extensions are neither advertised/applied nor executed.
- Unexpected tool-call output does not invoke capabilities or start a loop.
- The configured prompt replaces the hard-coded transcription prompt.
- Editable voice still requires explicit user submission.

