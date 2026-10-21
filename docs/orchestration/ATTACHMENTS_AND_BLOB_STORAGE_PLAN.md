# Attachments and blob storage

Status: implemented, with one open message-append failure-path finding recorded below. This is the single design/implementation record for the change.
No separate public blob-management service was introduced.
The Scala audio type is `MessagePayload.AudioAttachment`, with the JSON discriminator `AudioAttachment` and no override. Only the current schema is supported; there are no legacy decoders, migrations, or hash-verification exceptions.

## Goal

Preserve audio and other attachments without embedding their bytes in `objects.jsonl`.
Store blob content separately. Support messages referencing recordings/uploads before their bytes are complete.
Replace destructive audio redaction with references; keep the implementation small.

## Model

- Keep `MessageData` as the envelope: author, parent, timestamp, payload.
- Audio content is `MessagePayload.AudioAttachment(blobId: BlobId, format, description)` inside the payload companion.
- Put references inside payloads, not an additional `MessageData.attachments` field.
- Only `MessagePayload.AudioAttachment` is implemented for now. The generic `Attachment` payload has been removed; future file/image/document payloads can reuse `BlobId`.
- Allocate the blob ID when constructing the payload, before calling Storage.
- Generating an ID allocates identity only; it does not create storage or require complete content.
- `BlobId.fresh()` generates a UUID, distinct from `MessageId`; `BlobId.from` and JSON decoding require a canonical lowercase UUID safe as a path segment. It is not a content hash.
- Keep format and optional description on the audio payload.
- Keep blobs minimal: identity and bytes only. No blob metadata, lifecycle status, stored size, or duration.
- Do not mutate an already-persisted message as recording progresses.
- A future text-plus-attachments message should compose content inside its payload, not its envelope.

## Reading blobs (interface refinement)

Expose `readBlob(id): IO[YallmoError, Blob]` through `Orchestrator`; return all stored bytes in memory, not a stream.
Implemented model:

```scala
final case class Blob(id: BlobId, bytes: Chunk[Byte])
```

Storage owns duplicate-ID enforcement and reports missing files. Orchestrator keeps no in-flight ID registry.
There is no metadata or lifecycle/completeness/integrity tracking. Reading a stored blob returns its bytes, including bytes preserved from failed or cancelled producers.
Whole-blob reads are an intentional initial simplification; providers still enforce their own input-size limits.

## Append API

Keep ordinary message `append`. The three ingestion operations live directly on `Orchestrator`:

```scala
appendWithBlobBytes(sessionId, agentId, message, bytes)
appendWithBlobStream(sessionId, agentId, message, stream)
appendWithBlobWriter(sessionId, agentId, message, write => produce(write))
```

- The extra argument is respectively `Chunk[Byte]`, `ZStream`, or a producer callback receiving `write: Chunk[Byte] => IO[YallmoError, Unit]`.
- Orchestrator obtains the blob ID from the supplied payload; callers do not repeat it.
- No separate public `createBlob` / `writeBlob` workflow.
- Initially require exactly one blob reference in the supplied message; defer multiple-blob binding APIs.
- Preserve producer environment requirements, sequential chunk consumption, scoped writers, and typed errors. Streams must be finite and fit in memory. Generic ingestion has no enforced size limit; callers must bound input before buffering. Provider limits are checked later, after storage.
- The writer is valid only during its callback; Orchestrator buffers the bytes and owns completion.
- There is no `BlobAppend`, `BlobReader`, or `BlobWriter` trait; read/write dependencies are plain functions.
- Storage has separate message appends and `append(blob: Blob): IO[YallmoError, Unit]`, plus `readBlob(id)`. It does not accept streams or producers.
- `StorageFS` and `StorageInMemory` implement blob persistence directly alongside messages; there is no separate blob storage service.
- `Blob`, `BlobId`, and `Message` stay in the model package, not inside Storage.
- `AgentTurnRequest.readBlob: BlobId => IO[YallmoError, Blob]` is an explicit read function, not serialized content. `Orchestrator.turn` and the REPL runner supply it; direct adapter callers can supply their own function.
- Capture uses a REPL-local `CapturedAudio(input, bytes)` value; only `input` is a message payload.

## Lifecycle and storage

1. Validate the message and its single blob ID in the Orchestrator.
2. Persist the message containing that reference.
3. Collect the bytes, stream, or writer callback into memory; do not write chunks to Storage.
4. Close the writer and append one `Blob` containing all collected bytes to Storage.

Failed, cancelled, and defective producers still persist the bytes collected so far, then propagate their original outcome after successful storage. Stored bytes carry no lifecycle status.
Until step 4, the blob is not stored, so all reads report Missing. Storage rejects duplicate IDs when the full blob is submitted.
Message and blob appends are separate: if Storage refuses a duplicate blob, the already-appended message remains and existing blob content is unchanged.
If the process exits before step 4, the message remains but buffered bytes are not recoverable.
If storing the blob fails, that storage error is returned instead of the producer outcome; message persistence is not rolled back.
See the open finding below for the current deviation when the message append itself fails.

The message is visible before ingestion finishes. The append effect returns after ingestion finishes; callers can fork it for concurrent production.
Producer failure after message persistence keeps both the message and collected bytes; it is not converted into a successful ingestion result.
Filesystem Storage uses `<storage>/blobs/<BlobId>` as the filename and stores exactly the raw bytes inside it. No JSON encoding, framing, per-blob directory, or metadata file.
Blob files are created exclusively to reject duplicate IDs and written directly. Blob files and their directory use filesystem-default permissions (including the process umask); StorageFS does not set them explicitly. Existing object-store protections on the storage root remain unchanged. Reads overlapping writes, or reads after failed filesystem writes, may return partial bytes: there is no atomic publication or integrity check. Wait for successful append before relying on all supplied bytes being stored.
In-memory Storage stores blobs in memory.
Providers resolve references at request construction and report missing content or invalid audio input.
Do not imply that this storage change alone adds provider audio-streaming support.

## Filesystem concurrency contract

- Exactly one Storage service owns each root; no other process writes to it. There is no internal append semaphore or shared lock registry.
- Callers serialize message appends and session-metadata mutations across the entire root, including different sessions. One service instance does not prevent overlapping fiber calls.
- Overlapping mutations can lose session-head/membership updates or overwrite/interleave `objects.jsonl` records. Atomic session-file replacement does not make the read/modify/write sequence transactional or provide rollback.
- If concurrent writers are introduced, coordinate all message/session mutations at the storage boundary and add overlapping-write tests. Cross-process writers additionally need filesystem locking or a transactional backend.

## Transcription flow

Agreed ordering: append audio with bytes, invoke transcription, then append the transcript.

```mermaid
flowchart TD
    Capture[Capture audio bytes and create AudioAttachment with BlobId] --> Append[appendWithBlobBytes: persist message and store blob]
    Append --> Read[readBlob: resolve full audio for the provider]
    Read --> Invoke[Invoke transcription agent]
    Invoke --> Success{Valid transcript?}
    Success -->|Yes| Save[Append assistant transcript as child of audio message]
    Save --> Draft[Insert transcript into editable draft]
    Success -->|No| Error[Report failure; leave audio unchanged]
```

The generated transcription session stays separate from active chat. An inference failure leaves the audio message/blob unchanged; it does not create a successful transcript or change the draft.
Append/storage failures are reported before inference or draft insertion, as applicable.

For streaming ingestion, message visibility does not wait for the full recording:

```mermaid
sequenceDiagram
    participant Caller
    participant Orchestrator
    participant Storage
    Caller->>Caller: Create AudioAttachment with stable BlobId
    Caller->>Orchestrator: appendWithBlobStream / appendWithBlobWriter
    Orchestrator->>Storage: Append message containing BlobId
    Note over Caller,Storage: Message is visible before bytes are complete
    Caller->>Orchestrator: Produce chunks into memory
    Orchestrator->>Storage: append(Blob with all collected bytes)
    Orchestrator-->>Caller: Append result or error
```

## Persistence and identity

- `objects.jsonl` stores complete message envelopes with references, never blob bytes.
- Message IDs remain hashes of immutable `MessageData`, including references; hashing does not resolve blob content.
- Verify new audio message hashes normally; remove the new-format audio-only exception.
- Verify every message hash using the current schema. No old-format compatibility path is maintained. Old inline/redacted `AudioInput` envelopes cannot be decoded: back up the old store and use a fresh storage root; no automatic migration is supplied.
- These hashes protect message envelopes/references, not blob contents. Blob modification is not detected by message verification.
- Recordings remain stored after inference failure, interruption after ingestion, or draft discard. There is no automatic blob cleanup, garbage collection, or public recording-deletion API. Protect the whole storage root and its backups.
- Existing `append` accepts payloads because Storage constructs author/parent/timestamp and persists the envelope.
- Existing envelopes are not lost on reload, but re-appending their payloads does not preserve original timestamps, parents, or IDs.
- A separate envelope-preserving import API is outside this change.

## Remove audio-redaction methods and assumptions

Paths below are relative to their module's `src/main/scala/fmgp/yallmo/` directory.

| Location | Change |
| --- | --- |
| `model: model/Message.scala` | Use nested `MessagePayload.AudioAttachment` with a blob reference; messages have no destructive projection methods. |
| `core: orchestration/storage/CanonicalArtifactHash.scala` | Stop projecting retained values; verify new reference-based audio normally. |
| `core: orchestration/storage/StorageJson.scala` | Remove redaction during both encoding and decoding. |
| `core: orchestration/storage/StorageInMemory.scala` | Retain messages unchanged; support process-local blob content. |
| `repl: repl/voice/Transcriber.scala` | Replace identity-before-redaction assumptions and ordinary audio append with blob-aware append. |
| `core: provider/oai/OAILlmProvider.scala` and `OAIChatCompletionsMapper.scala` | Resolve blobs instead of reading `audio.bytes`; replace empty/redacted-audio checks with availability checks. |
| `core: LlmProviderPayloadDebug.scala` | Replace audio stripping with reference-based diagnostic capture, then remove `redactAudio`. |
| `repl: repl/debug/ReplDebug.scala` and `repl/terminal/TerminalMessageRenderer.scala` | Show references and metadata without resolving bytes. |

Provider wire JSON still contains resolved/base64 bytes. Audio debug requests are built from references before wire encoding; accidental inline-audio capture is refused rather than stripped.
Keep credential protection in `ProviderConfig.scala` / `OpenAiCodexAuth.scala`, and continuation-ciphertext protection in provider/REPL debug output.
Do not remove the shared debug `redact` helper while it still protects unrelated sensitive fields.
Audit remaining byte accesses, redaction calls, comments, and tests across model/core/repl during implementation.

## Documentation and verification

Update `docs/REPL.md`, `browser-STT-TTS/README.md`, and these files under `docs/transcriber/`:
`TRANSCRIBER_AS_AN_AGENT_PLAN.md`, `DEBUG_BY_MESSAGE_ID_PLAN.md`, and `BROWSER_STT_TTS_PLAN.md`.
Mark old discard/hash-exception decisions superseded; annotate `TRANSCRIBER_AS_AN_AGENT_EXECUTION_PLAN.md` and `BROWSER_STT_TTS_EXECUTION_PLAN.md` without rewriting historical results.
Update affected model, storage, provider, voice, rendering, and debug tests.
Cover all three append forms, early message visibility, stable IDs, persistence/reopen, failure/cancellation, duplicate IDs, missing blobs.
Assert no blob bytes in JSONL or default diagnostics, unchanged credential/continuation protection, and working new-format message hash verification.
Run focused suites, downstream compilation, and targeted formatting; no real-device/provider-streaming claims without manual verification.

## Validation record

- Post-format `sbt --batch 'model/testOnly *; core/testOnly *; repl/testOnly *'`: 24 model + 205 core + 206 REPL tests passed (435 unique tests), with the old-format compatibility test removed.
- Targeted scalafmt ran on changed Scala sources; `git diff --check` passed during implementation.
- Covered independent full-blob Storage appends, buffering before persistence, bytes/stream/writer ingestion, producer environments, early visibility, failed/cancelled/defective producers, closed writers, duplicates, full reads/reopen, opaque raw-byte files, missing content, private storage-root permissions, hash checks, provider resolution, and reference-only diagnostics.
- No real microphone/device or external-provider verification is claimed.

### Documentation review validation

- Re-ran `sbt --batch 'model/testOnly *; core/testOnly *; repl/testOnly *'`: **24 model + 205 core + 206 REPL = 435 unique tests passed**. This compiles affected main/test sources; no live provider or microphone verification was performed. Log: `/tmp/yallmo-attachments-doc-review-tests.log`.
- Browser checks: `node --test browser-STT-TTS/*.test.js` passed **42 tests**; all browser JavaScript passed `node --check`. Log: `/tmp/yallmo-attachments-doc-review-node.log`.
- Audited **39 repository Markdown documents** (excluding ignored archives/dependencies); all **87 local link targets** resolve. Both `git diff --check` and `git diff --cached --check` passed. No Scala sources were changed by this review, so no formatter run was needed.
- Reviewed repository Markdown for affected contracts and moved links. Updated user guides, configuration/capability and orchestration references, visualization/future transport boundaries, and historical-plan annotations without rewriting past validation results.
- Review changes are documentation-only; no implementation fix is included for the finding below.

## Open review finding: failed message append still submits an empty blob

**P2 — `core/src/main/scala/fmgp/yallmo/orchestration/OrchestratorLive.scala`, `appendWithBlobWriter`.**
The captured `exit` currently includes both message append and production. If the message append fails after initial
validation, the producer never runs, but finalization still calls `storage.append(Blob(blobId, Chunk.empty))`.
This consumes the ID without a successfully appended message, so retrying that reference can fail with `BlobAlreadyExists`.
If that blob append also fails, it can mask the original message-persistence error.

Confirmed with a temporary fault-injection suite outside the repository: after opening a valid empty filesystem session,
replace `objects.jsonl` with a directory, invoke blob ingestion, and observe a failed message append, no producer call,
an empty session, and a readable empty blob. The probe passed (reproducing the bug); it is not part of the 435-test gate.
Log: `/tmp/yallmo-blob-append-failure-review-probe.log`.

Follow-up: append the message successfully before capturing the producer's exit/finalizing its buffer; add a permanent
regression asserting that a failed message append never submits a blob or consumes its ID. Retain partial-byte
preservation for producers that fail **after** successful message persistence.

## Compatibility summary

Source compatibility changes: the audio subtype is now `MessagePayload.AudioAttachment` (formerly top-level `AudioInput`) and accepts a reference rather than bytes; REPL capture/transcription uses `CapturedAudio`. Direct structured adapter calls supply `AgentTurnRequest.readBlob`; prompt-based audio inference accepts an optional `readBlob` function. `Orchestrator.turn` and REPL native-runner binding supply the read function automatically.
