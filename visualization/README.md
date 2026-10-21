# Yallmo conversation visualization

This Vite application renders the actual local `../zzz` storage snapshot. It does not use fixture or mock conversations.

`npm run generate-data` reads `zzz/objects.jsonl` and `zzz/refs/sessions/` and writes an API-shaped, browser-safe projection to `src/generated/zzz-conversation.json`. The browser consumes that projection only; it does not read or parse the object store.

Audio uses the current `MessagePayload.AudioAttachment` JSON variant. The generator treats it as an `unknown` node and
shows reference/format/description JSON; it never reads `blobs/`, resolves bytes, or offers audio playback. Message IDs
hash envelopes/references, not recording contents. Use only current-schema stores; this generator is not a legacy-audio
migration or general-purpose sanitizer. Generated transcript/tool/description text can be sensitive: do not publish it.
See [Attachments and blob storage](../docs/orchestration/ATTACHMENTS_AND_BLOB_STORAGE_PLAN.md) and the
[future graph API plan](../docs/visualization/CONVERSATION_DAG_VISUALIZATION_PLAN.md).

```bash
npm install
npm run dev

# From the root folder
npm --prefix visualization install
npm --prefix visualization run dev
```

Regenerate the snapshot after the REPL writes new storage data:

```bash
npm run generate-data
```

`npm run build` and `npm test` regenerate it automatically.
