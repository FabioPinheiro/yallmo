# Pi Hermes Memory Package

Sources:

- https://pi.dev/packages/pi-hermes-memory
- npm package `pi-hermes-memory` README

## Purpose

`pi-hermes-memory` ports Hermes-inspired memory features to Pi:

- persistent memory,
- session search,
- secret scanning,
- failure/correction/insight memories,
- procedural skills,
- background learning,
- auto-consolidation,
- global and project memory tiers.

Install:

```bash
pi install npm:pi-hermes-memory
```

## Main Commands

```text
/memory-index-sessions    # index past Pi sessions
/memory-sync-markdown     # backfill Markdown memories into SQLite search
/learn-memory-tool        # onboarding skill
/memory-insights          # show memory/user profile
/memory-skills            # interactive skill manager
/memory-consolidate       # manual consolidation
/memory-interview         # fill user profile
/memory-preview-context   # preview memory policy/legacy injection
```

## Storage Model

Two-tier memory:

| Tier | Location | Use |
|---|---|---|
| Global | `~/.pi/agent/pi-hermes-memory/` | facts that apply everywhere |
| Project | `~/.pi/agent/projects-memory/<project>/` | repo-specific facts |

Core files:

- `MEMORY.md` — durable facts, project conventions, tool quirks, lessons.
- `USER.md` — user profile and preferences.
- skills — Pi-native `SKILL.md` procedures.
- `sessions.db` — session history and extended/search mirror via SQLite FTS5.

## Policy-Only Mode

By default, the extension uses `memoryMode: "policy-only"`.

Instead of injecting all memory into the system prompt, it injects a memory policy telling the agent when and how to call `memory_search`.

This keeps first-turn token usage low while preserving access to memory on demand.

Legacy behavior is available with:

```json
{ "memoryMode": "legacy-inject" }
```

## Tools

### `memory`

Actions:

- `add` — append new entry,
- `replace` — update matched entry,
- `remove` — delete matched entry.

Targets:

- `memory`,
- `user`.

### `memory_search`

Searches extended/core memory mirror by keyword and category.

Categories include:

- `failure`,
- `correction`,
- `insight`,
- `preference`,
- `convention`,
- `tool-quirk`.

### `session_search`

Searches past Pi sessions. Default uses SQLite FTS5 snippets. Optional `anchors` variant returns compact source anchors from session JSONL files.

### `skill`

Creates and manages reusable Pi-native skills:

- `create`,
- `view`,
- `patch`,
- `edit`,
- `delete`.

Skills are stored as `SKILL.md` in global or project memory skill directories.

## Background Learning

The extension reviews activity:

- every 10 turns by default,
- or every 15 tool calls,
- immediately for detected corrections.

After complex tasks, it may ask whether to save a reusable procedure as a skill.

## Secret Scanning

Memory and skills are scanned before persistence to prevent API keys, tokens, SSH keys, and malicious/prompt-injection-like content from being saved and later resurfaced.

## Auto-Consolidation

When core Markdown memory reaches character limits, the extension can spawn a one-shot `pi.exec()` consolidation flow, merge/remove stale entries, reload from disk, and retry the write.

## Important Distinction

Pi Hermes Memory is a Pi extension inspired by/ported from Hermes concepts. It is not identical to upstream Hermes internals. Always distinguish:

- upstream Hermes prompt assembly and `memory_tool`,
- Pi extension tools/config/storage/hooks.
