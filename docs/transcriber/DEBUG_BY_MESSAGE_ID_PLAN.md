# Future: inspect persisted messages by hash

Status: future work only; not part of the transcriber-as-agent implementation.

## Goal

Inspect a stored message without selecting/resuming its session or inserting it into inference context. This makes separately retained transcription exchanges inspectable without changing the active conversation, and also supports messages not associated with a session if sessionless writes are added later.

## Proposed command

- Preserve `/debug print`, `/debug export`, and `/debug request` behavior for the current conversation.
- Add `/debug message <sha256:64-lowercase-hex> [print|export]`, defaulting to `print`.
- Initially show exactly the addressed message: ID, author, timestamp, parent ID, and safe payload. Display the parent hash for a follow-up lookup; do not walk the entire ancestor chain automatically.
- Do not support abbreviated hashes, arbitrary filesystem paths, replay, editing, or provider-request reconstruction in the initial version. `/debug request` remains runtime capture, not something reconstructed by loading attachment bytes.

## Small implementation sequence

1. Expose a typed, read-only message lookup by `MessageId` through `Orchestrator` and the storage abstraction. Reuse the filesystem object index rather than scanning session names; give in-memory storage equivalent lookup semantics. Keep internal store APIs private and preserve existing hash/structural checks, with no audio-specific exceptions.
2. Extend typed command parsing, help, and completion. Validate full hashes with `MessageId.from`; distinguish invalid hashes, not-found messages, and storage failures without echoing secrets.
3. Reuse safe debug rendering/export for a single message. Audio contains a blob reference and descriptive metadata; inspection does not load content; transcript/description text remains visible and may be sensitive. Do not follow parent IDs automatically or fetch anything remotely.
4. Leave the selected agent, active session/head, run context, provider continuation, draft, and latest provider-request capture unchanged. Lookup/export must never invoke inference, tools, RAG, skills, or extensions.
5. Document how to find IDs. At minimum, include IDs in the message inspector and document their location in local `objects.jsonl`. Consider a small local transcription-success receipt containing its session/message IDs, without dumping transcript text or bytes to logs; agree its UI before implementation.

## Acceptance and tests

- Parser tests cover existing commands, valid full hashes, malformed hashes, optional print/export, and extra arguments.
- Lookup works without an active chat/selected agent, across sessions, and after filesystem reopen. Unknown IDs fail locally with an actionable error.
- A transcript hash shows its ordinary assistant message and audio parent ID; looking up that audio parent shows its reference and metadata, without reading bytes.
- Print/export agree on reference-only rendering and continuation protection. New audio and non-audio tampering fail normal storage verification.
- State before and after inspection is identical; test with a fake provider/capabilities that fail if invoked.
- Add narrow grammar/storage/UI suites, then full tests and formatting according to project conventions.

Related: [TRANSCRIBER_AS_AN_AGENT_PLAN.md](TRANSCRIBER_AS_AN_AGENT_PLAN.md).
