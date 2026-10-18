# Terminal UI (TUI) plan

> Planning document only. This directory deliberately contains no TUI implementation yet.

## Purpose

Provide a full-screen, local terminal client for the existing typed `fmgp.yallmo.repl` application, plus an explicitly opt-in local PTY workspace and a multi-agent orchestration console. The ordinary conversation view remains a presentation adapter; PTY processes and agent workers are separately governed capabilities, never incidental UI features.

```text
┌──────────────── local TTY ───────────────────────────────────────┐
│                                                                    │
│ TUI: conversation │ agent graph │ PTY panes │ approvals            │
│       │                 │             │                            │
│       ▼                 ▼             ▼                            │
│ ReplApplication    AgentOrchestrator  PtyMultiplexer               │
│       │                 │             │                            │
│       ▼                 ▼             ▼                            │
│ ReplSession/Runner  isolated runners  supervised child processes   │
│       └─────────────── trusted tools, providers, policies ────────┘
└──────────────────────────────────────────────────────────────────┘
```

The current REPL has one JLine client and no UI-selection flags. Any future full-screen interface must require an interactive local TTY and fail clearly on a dumb terminal or redirected input/output. Its integration must not reintroduce parallel terminal modes or fallback clients.

## Scope and boundaries

The future TUI remains a presentation adapter over the same application boundary as the sole current client, `TerminalRepl`:

```text
ReplApplication ─────── TerminalRepl
                       (future full-screen evolution planned)
```

It owns terminal setup, screen state, key handling, and rendering. `ReplApplication` remains the single owner of command parsing, session transitions, transcript state, and tool availability.

The initial conversation pane is not itself a shell, but the planned **PTY workspace** is an explicit terminal-emulation and process-management feature. It is local-only at first; it is not a web client or remote-control protocol and it does not change the current terminal client until explicitly implemented.

The existing `bash` agent tool remains a bounded, non-interactive command execution capability. A PTY-backed process has a distinct capability, session model, and security boundary; it must be created through `PtyMultiplexer`, never by changing `bash` or by allowing arbitrary model text to reach a terminal.

## User experience

The first usable screen should retain the conversation while offering an editable, multi-line composer and explicit status.

```text
┌ yallmo ─ workspace: /…/yallmo ─ tools: 4 available ──────────────┐
│                                                                    │
│ You                                                                │
│ Explain the tool catalog.                                          │
│                                                                    │
│ Assistant                                                          │
│ The catalog separates installed tools from advertised tools…       │
│                                                                    │
│ Tool: repositoryChanges                                            │
│ {"exitCode":0,"output":"…"}                                    │
│                                                                    │
│ [conversation scrolls; PgUp/PgDn or wheel if supported]           │
├────────────────────────────────────────────────────────────────────┤
│ > How is `bash` constrained?                                      │
│   Tab: command completion  Enter: submit  Esc: cancel/edit menu   │
├────────────────────────────────────────────────────────────────────┤
│ ready · 12 messages · Ctrl+K help · Ctrl+C interrupt/exit         │
└────────────────────────────────────────────────────────────────────┘
```

### Initial features

1. **Transcript viewport**
   - Render user, assistant, tool-result, and RAG-result messages as distinct, accessible text blocks.
   - Preserve transcript ordering exactly; do not infer or reorder model/tool activity.
   - Wrap long text to the available width, preserve code blocks/JSON, and make truncation explicit rather than silently losing text.
   - Support scrolling, jump to latest, and an unread/new-output indicator when the user is inspecting earlier output.

2. **Multi-line prompt composer**
   - Insert newlines without submitting (for example, `Alt+Enter`); submit with `Enter` when the input is complete.
   - Maintain in-memory prompt history for the session; persistent history is a later, opt-in decision and must not replace JLine's existing history format without migration.
   - Use `ReplGrammar.complete` for `/` command completion and show a selectable completion popup.
   - Treat `//` exactly as the core grammar does: it sends a literal prompt that begins with `/`.

3. **REPL commands and diagnostics**
   - Render `/help`, `/history`, `/tools`, `/debug`, `/debug export`, `/new`, and `/exit` from their existing semantic `ReplEvent`s.
   - Make `/tools` a readable availability panel, including unavailable-tool reasons.
   - Show input/command errors inline with the grammar's suggestions.
   - Retain `/debug export`'s existing local-file semantics; display the resulting path or write failure.

4. **Run lifecycle**
   - Display an unambiguous busy state from submission until the turn completes or fails.
   - Disable duplicate submission while a turn is active; still allow transcript scrolling and the planned interrupt action.
   - Add a visible interrupt affordance only after a core cancellation contract exists. Until then, `Ctrl+C` keeps the established terminal-client behavior (a second consecutive interrupt exits).
   - Render failures without clearing the draft or transcript.

5. **Terminal behavior**
   - Redraw on terminal resize while preserving the draft, focus, and nearest sensible transcript position.
   - Enter and reliably restore terminal modes on normal exit, error, and interruption.
   - Use colour only as enhancement; labels, borders, and text must preserve meaning when colour is unavailable.
   - Provide a compact small-terminal message instead of attempting an unusable layout.

6. **PTY workspace and multiplexer**
   - Provide windows/tabs and resizable panes. Every pane is a supervised PTY process with a title, cwd, size, owner, start time, lifecycle state, bounded scrollback, and durable audit metadata.
   - Support a configured, allowlisted initial command (for example the login shell, `sbt`, or a project task); do not make a free-form command launcher the first UI.
   - Render ANSI/VT output in a PTY pane, forward only the focused pane's keyboard input, propagate resize events, and map `Ctrl+C`/EOF/signals to the selected process rather than to the TUI.
   - Offer explicit actions to create, rename, split, focus, detach, terminate, and close panes. Closing a pane must show whether its child process will receive `SIGHUP`/termination and must clean up all descendants.
   - Assign one input lease per pane. Agent observation may be read-only, but concurrent humans/agents must not interleave bytes into the same terminal.

7. **Multi-agent workspace**
   - Show an orchestration graph/list containing a coordinator, workers, dependencies, state, budget use, artifacts, approvals, and final result.
   - Let the operator create a bounded plan, start/pause/cancel/retry individual workers, inspect each worker's transcript and tool activity, and promote a worker result into the coordinator context.
   - Use one isolated workspace/worktree per mutating worker by default. Shared writes require an explicit serialized ownership/merge policy.
   - Present approval requests and conflicts in a dedicated queue; an agent must never approve its own privileged action.

8. **Keyboard help and accessibility**
   - Provide an always-reachable keybinding overlay (`Ctrl+K` proposed) and a visible status hint.
   - Keep a predictable focus model: transcript, composer, completion popup, modal/overlay, agent graph, and PTY pane.
   - Avoid mouse-only operations. Mouse scrolling may be optional and never required.

## PTY capability model

PTY access is intentionally stronger than the existing `bash` tool. The UI must show the active level for every pane and agent; a capability is never inferred from being displayed in the TUI.

| Level | Capability | Default | Model access |
| --- | --- | --- | --- |
| 0 | No process: normal TUI/REPL only | enabled | none |
| 1 | Existing one-shot `bash` tool with timeout/output bounds | existing policy | only when advertised |
| 2 | Supervised PTY running an allowlisted program; operator owns input | opt-in | read-only observation only |
| 3 | Operator-interactive login shell in a configured local workspace | explicit confirmation | none by default |
| 4 | Agent may request a PTY command or write input | disabled until a separate approval/policy implementation | per-action approval and audit |

Levels 2–4 are local-only in the first release. Level 4 must not be implemented as direct keystroke forwarding: each request needs an identity, a declared purpose, process/pane target, input bounds, approval decision, and audit record.

## State and event model

Do not make terminal escape sequences or rendered strings the application protocol. The TUI consumes typed input and `ReplEvent` values, then produces a screen model.

```text
key/resize ──> TuiController ──> TuiState ──> Renderer ──> terminal
                     │                ▲
                     │ submit         │ ReplTransition(events)
                     ▼                │
               ReplApplication ───────┘
```

The controller should maintain UI-only state separately from `ReplState`, for example:

- current draft and cursor/selection;
- active focus and overlay;
- completion candidates and selected candidate;
- transcript viewport offset and whether it is following the newest output;
- dimensions, terminal capability flags, and transient notification;
- active-turn state once cancellation has a defined API;
- `PtyWorkspace` panes, layout tree, selected pane, scrollback view, and per-pane input lease;
- `OrchestrationView` task graph, worker summaries, selected worker, approval queue, and artifact references.

`ReplState` remains opaque application/session state. The TUI must update it only with `ReplTransition.state` returned by `ReplApplication.submit`. PTY and orchestration state have independent typed services and event streams; neither may mutate a REPL transcript behind `ReplApplication`'s back.

### Event rendering matrix

| Core event | TUI presentation | State effect |
| --- | --- | --- |
| `Help` | help panel or transcript block | none |
| `DebugSnapshot(Display)` | scrollable debug overlay | none |
| `DebugSnapshot(Export)` | success/failure notification | none |
| `Transcript` | transcript viewport content | none |
| `ToolAvailability` | tools panel or formatted transcript block | none |
| `SessionReset` | clear UI transcript, draft, completion, and viewport | replace local view of session |
| `AgentTurn` | append intermediate RAG/tool messages and final answer | follow latest unless user scrolled away |
| `InputRejected` | inline composer error and suggestions | preserve draft |
| `AgentFailed` | failure notification/transcript block | preserve draft and session |
| `ExitRequested` | restore terminal and exit | close client |

The exact screen model may normalize these events for rendering, but it must not discard message content or convert them into an alternative REPL protocol.

## Multi-agent orchestration

The current `AgentRunner` owns one trusted, serial action loop. Do **not** add parallelism by making one agent's `LlmAction` batch concurrent: action ordering is currently deliberate because tools may mutate state. Instead, add a separate, typed `AgentOrchestrator` above independent worker runs.

```text
operator request
      │
      ▼
coordinator creates a bounded, reviewable task graph
      │
      ├── research worker (read-only context/workspace)
      ├── implementation worker (isolated worktree + limited tools)
      ├── test worker (isolated worktree + limited tools)
      └── review worker (fresh, read-only context)
      │
      ▼
artifact/result collector ──> conflict/approval gates ──> coordinator synthesis
```

Each node needs a stable `runId`, parent/dependency ids, agent profile, immutable static context/capability set, workspace/worktree reference, declared budget, lifecycle state, event log, artifacts, and terminal outcome. Workers communicate through named, typed result/artifact references—not shared mutable transcripts or copied terminal output. The coordinator receives only the summaries/artifacts explicitly selected by policy or the operator.

Scheduler rules:

- run only dependency-ready nodes, with a configured global concurrency limit;
- keep mutating workers isolated and grant each write path/worktree to one owner at a time;
- make retries create a new attempt linked to the failed one; never overwrite its evidence;
- propagate cancellation down the dependency tree, then record which processes/runs actually stopped;
- treat approval, merge, and conflict resolution as first-class blocked states, not generic failures;
- enforce per-worker and aggregate budgets for turns, actions, elapsed time, process count, PTY output, and eventually provider usage/cost;
- persist an audit event for scheduling, capability grant, process start/exit, approval, artifact hand-off, cancellation, and merge.

A PTY pane can be attached to a worker for **observation** (logs, an interactive test runner, or an operator debugging session), but terminal bytes are not the worker protocol. The worker's machine-readable result and artifact references remain authoritative.

## Rendering and implementation direction

The project already uses JLine 4 for terminal creation and line editing. Investigate its terminal/display APIs first so the future interface can reuse terminal capability detection and native-access requirements. Select a widget/layout library only if JLine cannot meet the required resize, redraw, input, and cleanup behavior.

Keep these responsibilities separate:

```text
TerminalTuiRepl       acquires/releases the local terminal and runs the loop
TuiController         maps keys, resize events, and typed domain events to TuiState
TuiLayout             derives panes and bounds from TuiState plus terminal size
TuiRenderer           paints a complete screen from a layout; no agent/session calls
TuiInput              decodes terminal keys and bracketed paste; no rendering
PtyMultiplexer        creates, resizes, signals, reads, writes, and reaps PTY processes
PtyTerminalEmulator   interprets the supported VT/ANSI subset into a pane screen/scrollback model
AgentOrchestrator     validates/schedules worker graph nodes and emits typed lifecycle events
ArtifactStore         records immutable worker outputs and references
ApprovalService       owns privileged capability decisions and audit records
```

All potentially blocking terminal reads must be isolated from the ZIO compute pool. Rendering and state transitions should be deterministic and unit-testable without a real terminal. The terminal resource must be restored with `ensuring`/scoped acquisition even if application submission, rendering, or input decoding fails.

## Concurrency and interruption

The current `ReplApplication.submit` performs a complete turn and returns a final `ReplTransition`; it has no progress or cancellation contract. The first TUI can therefore show a local "working" state but cannot honestly expose streaming tool activity or a Stop button.

Before adding those features, define a UI-neutral contract that supports:

```text
TUI ── start turn ──> application/session
 │                         │
 │ <── progress events ────┤  (optional later)
 │ ── interrupt(turn id) ─>│
 │ <── terminal result ────┘
```

Cancellation must identify the active turn, be idempotent, wait for child work to stop or report that it cannot, and leave the `RunContext` in a documented valid state. Do not implement cancellation by killing the terminal thread or by fabricating a partial assistant response.

## Security and privacy

TUI mode is local, but it displays sensitive material produced by tools, models, and PTY processes. It must:

- inherit the REPL's static tool advertisement; it must never add capabilities;
- make tool outputs, worker output, and raw PTY output visually distinguishable from assistant text;
- avoid logging drafts, transcript content, or terminal escape sequences by default;
- preserve the existing debug redaction/export behavior;
- create PTYs with an explicit cwd, minimal allowlisted environment, configured executable/arguments, process group, resource limits, output/scrollback limits, and parent-child cleanup policy;
- sanitize or disable terminal control sequences with side effects outside the pane (for example clipboard, title, hyperlink, and device-control sequences) while retaining only the emulator subset the UI supports;
- make PTY input ownership exclusive and auditable; agents have no input authority without a separate approved Level-4 policy;
- never expose PTYs or orchestration controls remotely until authentication, authorization, transport security, tenancy, rate/output limits, and an audit model are designed.

A PTY backend requires native/JVM platform investigation before implementation: Unix PTY allocation, Windows ConPTY support or an explicit unsupported-platform message, process-tree reaping, resize/signal semantics, and test doubles. Do not substitute `ProcessBuilder` pipes for a PTY; they do not provide terminal semantics.

## Delivery milestones

1. **Foundations:** document and test TTY detection, terminal acquisition/release, resize handling, key decoding, and a pure screen/layout model. Do not expose a separate terminal mode or startup flag for this plan.
2. **Read-only shell:** render a fixture transcript, status bar, help overlay, wrapping, scrolling, resize, and minimal-terminal fallback. Add screenshot/golden tests for the pure renderer.
3. **Interactive REPL:** add composer editing, submit flow, grammar-backed command completion, semantic event rendering, `/new`, `/exit`, and error recovery. Integrate through the single terminal-client startup path.
4. **PTY spike and policy:** choose/test a cross-platform PTY backend and a constrained VT emulator; specify Level-2 process policy, process-tree cleanup, output/control-sequence handling, audit events, and fake-backend tests. No agent PTY input.
5. **Local multiplexer:** implement allowlisted pane creation, splits/tabs/focus, resize and signal forwarding, bounded scrollback, input leases, and robust cleanup. Start Level 3 only after explicit confirmation UX and tests.
6. **Orchestration core:** define task graph, worker lifecycle/progress events, typed artifact hand-offs, budgets, scheduler, cancellation, approval gates, and workspace/worktree isolation. Keep it independent of PTY display.
7. **Orchestration TUI:** add task graph, worker inspector, approval queue, artifact browser, and optional read-only PTY attachment. Add operator actions for start/pause/cancel/retry and merge/conflict review.
8. **Privileged agent-PTY policy:** only after a separate approval, auditing, input-ownership, and threat-model review. This unlocks Level 4; it is not required for multi-agent orchestration.

## Test strategy

- Unit-test key-to-action mapping, focus changes, completion selection, viewport scrolling, wrapping, and every `ReplEvent` rendering decision as pure data transformations.
- Test startup policy: reject dumb/non-interactive terminals without selecting an alternate client.
- Use a fake terminal/input abstraction to test redraw ordering, resize preservation, terminal restoration, and error paths without a human TTY.
- Test the PTY service behind a fake backend for lifecycle, input lease, resize, signal, output bounds, escape-sequence filtering, audit, and process-tree cleanup. Run a platform integration matrix against Unix PTYs and Windows ConPTY if supported.
- Test the orchestrator with deterministic fake agents/tools: dependencies, concurrency limit, budget exhaustion, retry evidence, cancellation propagation, approval blocks, artifact handoff, write-owner conflicts, and coordinator synthesis inputs.
- Reuse `ReplApplication` tests to verify command semantics rather than duplicating grammar/session tests in the TUI.
- Run a small manual matrix on macOS/Linux terminals, narrow and wide windows, no-colour terminals, piped I/O, Ctrl+C, EOF, resize, bracketed paste, interactive TUI programs, and orphan-process detection.

## Acceptance criteria

- The terminal client requires a local interactive TTY, with no alternate modes or fallback clients.
- A user can submit prompts, use REPL commands, inspect prior messages, scroll, complete commands, reset, and exit without losing application semantics.
- PTY panes are explicitly created, locally supervised, bounded, auditable, resizable, and reliably reaped; no model has implicit PTY input access.
- Multi-agent work has a typed task graph, isolated worker contexts/workspaces, bounded scheduling, artifact-based handoff, approvals, cancellation, and an operator-visible audit trail.
- The TUI renders typed application, PTY, and orchestration events; it does not duplicate the agent loop or directly invoke providers.
- Terminal state is restored after normal exit, EOF, interrupts, and recoverable failures.
- Progress and interruption controls are added only when the underlying application offers a typed, tested lifecycle contract.
