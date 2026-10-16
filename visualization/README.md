# Yallmo conversation visualization

This Vite application renders the actual local `../zzz` storage snapshot. It does not use fixture or mock conversations.

`npm run generate-data` reads `zzz/objects.jsonl` and `zzz/refs/sessions/` and writes an API-shaped, browser-safe projection to `src/generated/zzz-conversation.json`. The browser consumes that projection only; it does not read or parse the object store.

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
