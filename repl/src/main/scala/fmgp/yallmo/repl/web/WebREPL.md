# Web REPL plan

> Planning document only. This directory deliberately contains no web implementation yet.

## Purpose

Provide a browser client for the same typed `fmgp.yallmo.repl` application used by terminal clients. The browser is a presentation and transport adapter, not a second agent loop and not a terminal emulator.

```text
browser UI <-> WebSocket adapter <-> ReplApplication <-> AgentRunner/tools/providers
```

The browser renders semantic REPL events itself. It must not consume terminal-formatted strings or ANSI escape sequences.

## Required core prerequisites

The first four terminal-independent contracts now exist; they must remain stable before adding web code:

1. **Done:** a typed REPL grammar that is the single source for parsing, help, and completion.
2. **Done:** `ReplApplication`, which receives typed input and emits semantic `ReplEvent` values.
3. **Done:** structured completion request/results, with replacement ranges and candidates.
4. **Done:** structured debug snapshots and exports. The browser downloads an export; it never asks the server to write a client-local file.
5. **Still required:** a clearly defined interrupt event and failure model.

Do not expose the terminal `Console` loop, JLine types, ANSI strings, or filesystem paths through the web protocol.

## Transport direction

Use WebSocket for bidirectional interactive traffic. Define a versioned, JSON-encoded envelope only after the core event model is stable.

Client-to-server messages will need at least:

- submit input;
- request completion for text plus cursor position;
- interrupt the active run;
- reset or request a transcript/debug snapshot;
- explicit client close.

Server-to-client messages will need at least:

- command/turn acknowledgement;
- transcript and assistant events;
- tool activity;
- completion results;
- debug snapshot/export payload;
- recoverable errors and terminal session closure.

The protocol should transmit semantic data, not pre-rendered terminal output. Include a protocol version from the first implementation.

## Browser experience

The initial browser client should be a normal conversation UI:

- editable prompt with browser-managed up/down input history;
- command completion popup driven by the shared grammar;
- transcript view driven by `ReplEvent` values;
- explicit Stop/Interrupt control;
- debug display and download;
- session reset/new-session control.

It should not initially embed a terminal emulator such as xterm.js. A terminal emulator is only justified if the product later exposes an actual shell/PTY, which is a separate security and protocol boundary.

## Session direction

Session lifetime, reconnect behavior, and durable persistence are unresolved architectural questions. The eventual WebSocket adapter must not force a decision prematurely:

- avoid making the browser the source of truth for the transcript;
- keep a stable session identifier in the transport shape;
- define reconnect and retention policy before enabling multi-client or durable sessions;
- serialize turns per session in the first implementation.

## Security gate before implementation

The current REPL can register powerful local tools, including Bash. A remotely reachable WebSocket endpoint must not be exposed before these are designed:

- authentication and authorization;
- origin/CSRF policy and TLS (`wss`);
- per-session capability/tool policy;
- rate, turn, and output limits;
- cancellation and disconnect handling;
- audit/redaction policy for transcript and debug exports.

A development-only localhost mode may be useful later, but it must still be explicit and clearly labelled.

## Implementation milestones

1. Finish and test the UI-neutral REPL core contracts.
2. Define the wire schema and compatibility/versioning policy.
3. Add a ZIO HTTP WebSocket adapter with scripted protocol tests.
4. Add a minimal browser client with submit, transcript rendering, completion, and interrupt.
5. Add authentication/capability policy before non-local exposure.
6. Decide persistence/reconnect semantics, then add durable sessions if required.

## Acceptance criteria

A browser and a terminal client can drive the same REPL session API and receive the same semantic results for command parsing, completion, transcript messages, debug snapshots, errors, reset, and interruption. Neither client imports or depends on the other client's rendering implementation.
