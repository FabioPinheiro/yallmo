# Orca main models

This describes the external Orca project, not Yallmo's implemented models. Source/ADR paths below are relative to an
Orca checkout. It is a source-based map of Orca's two core modules, not a replacement for its ADRs. It focuses on the public or load-bearing models in `tools` and `flow`;
backend wire DTOs and renderer-specific models are intentionally omitted.

**Type parameters:** `B` is a `BackendTag` (Claude, Codex, OpenCode, Pi, or
Gemini). It prevents a session or result from one backend being used with
another. `O` is the `JsonData`-backed structured output type requested from an
agent.

## 1. Tools: agent, turn, and conversation models

```mermaid
classDiagram
    direction LR

    class Agent {
      AgentConfig config
      String name
      run()
      chat()
      resultAs()
    }
    class AgentConfig {
      Model model
      Model cheapModel
      ToolSet tools
      AutoApprove autoApprove
      String systemPrompt
    }
    class AgentCall {
      autonomous
      interactive
      run()
    }
    class Chat {
      Agent agent
      SessionId id
      run()
      resultAs()
    }
    class AgentBackend {
      workDir
      SessionSupport sessions
      open()
      runAutonomous()
      runInteractive()
    }
    class TurnRequest {
      prompt
      SessionId session
      Dispatch dispatch
      TurnMode mode
      AgentConfig config
      outputSchema
    }
    class LiveTurn {
      TurnEvent events
      awaitResult()
      canAskUser
      cancel()
    }
    class AgentResult {
      output
      WireSessionId wireId
      usage
      model
    }
    class SessionSupport {
      dispatchFor()
      rehydrate()
      register()
      persistableWireId()
    }
    class SessionId
    class WireSessionId
    class BackendTag
    class ToolSet {
      ReadOnly
      NetworkOnly
      Full
      NoTools
    }
    class AutoApprove {
      All
      Only()
    }

    Agent --> AgentConfig : value
    Agent --> AgentBackend : same backend for siblings
    Agent --> Chat : mints
    Agent --> AgentCall : resultAs[O]
    Chat --> Agent : keeps
    Chat --> SessionId : keeps
    AgentCall --> AgentBackend : invokes
    AgentBackend --> SessionSupport : owns
    AgentBackend --> TurnRequest : opens from
    AgentBackend --> LiveTurn : opens
    LiveTurn --> AgentResult : completes with
    SessionSupport --> SessionId : maps client id
    SessionSupport --> WireSessionId : maps resume id
    AgentResult --> WireSessionId : reports
    Agent --> BackendTag : parameter B
    AgentConfig --> ToolSet
    AgentConfig --> AutoApprove
```

`Agent` is an immutable, configured handle. Calls such as `withReadOnly`,
`withModel`, and `cheap` create sibling agents that retain the same
`AgentBackend`, so they share session bookkeeping and the lifecycle close
latch. `LiveTurn` is deliberately one in-flight turn, **not** a conversation;
`SessionSupport` determines whether its `SessionId` starts fresh or resumes a
backend conversation.

## 2. Flow: ambient context, stage authority, and durable state

```mermaid
classDiagram
    direction LR

    class FlowContext {
      Agent planningAgent
      Agent codingAgent
      Agent reviewAgent
      GitTool git
      GitHubTool gh
      FsTool fs
      stackSettings
      reviewerCatalog
      userPrompt
    }
    class AgentSet {
      claude
      codex
      opencode
      pi
      gemini
    }
    class FlowControl {
      ProgressStore progressStore
      SessionStore sessionStore
      withStage()
      claimSessionKey()
      claimTurn()
    }
    class InStage {
      sharedToken
    }
    class WorkspaceWrite {
      exclusiveToken
    }
    class StageFrames {
      stagePath
      ownerThreadChecks
    }
    class ProgressStore {
      load()
      writeHeader()
      upsertEntry()
    }
    class ProgressLog {
      ProgressHeader header
      entries
      stageStarts
      PublishedWork published
    }
    class ProgressHeader {
      branch
      startingBranch
      startingCommit
      userPrompt
      flow
    }
    class StageEntry {
      StagePath id
      RawJson resultJson
    }
    class SessionStore {
      records()
      upsert()
    }
    class SessionRecord {
      name
      StagePath stage
      id
      seed
      resumeWireId
      BackendTag backend
    }
    class RunEventLog {
      records()
      upsert()
      runSucceeded()
      finish()
    }
    class RunEvent {
      attempt
      timestamp
      outcome
    }
    class SessionKey {
      name
      StagePath stage
    }
    class FlowSession {
      Chat chat
      SessionKey key
      run()
      resultAs()
    }
    class GitTool
    class GitHubTool
    class FsTool

    FlowContext --|> AgentSet
    FlowContext --> AgentSet : role agents resolve from
    FlowContext --> GitTool
    FlowContext --> GitHubTool
    FlowContext --> FsTool
    FlowControl --> ProgressStore
    FlowControl --> SessionStore
    FlowControl ..> StageFrames : stage path + thread rules
    ProgressStore --> ProgressLog : reads/writes
    ProgressLog *-- ProgressHeader
    ProgressLog *-- StageEntry
    FlowControl --> InStage : stage mints
    FlowControl --> WorkspaceWrite : stage mints
    RunEventLog --|> SessionStore
    RunEventLog --> RunEvent : appends JSONL events
    SessionStore --> SessionRecord : projects records
    SessionRecord --> SessionKey : reconstructed key
    FlowSession --> Chat : durable wrapper around
    FlowSession --> SessionKey
    FlowSession --> SessionStore : rehydrates/persists
```

`FlowContext` is broadly shareable runtime access. `FlowControl` is separate
and exclusive: it is the authority to begin a stage. Starting a stage supplies
both narrower tokens: `InStage` gates agent calls and may cross a review fork;
`WorkspaceWrite` gates workspace mutations and cannot cross one. The
`ProgressLog` is committed branch state. `RunEventLog` is the machine-local,
append-only JSONL cache; as a `SessionStore`, it projects `SessionRecord`s from
that log because backend session handles are machine-local.

## 3. How a durable agent call crosses the two modules

```mermaid
sequenceDiagram
    participant S as Flow script
    participant C as FlowControl
    participant F as FlowSession
    participant A as Agent / Chat
    participant B as AgentBackend
    participant SS as SessionSupport
    participant P as Provider
    participant E as SessionStore

    S->>C: stage(name)
    C-->>S: InStage + WorkspaceWrite
    S->>F: agent.session(name, seed)
    F->>E: find SessionRecord by SessionKey
    E-->>F: prior record or none
    F->>A: run effective prompt
    A->>B: runAutonomous(prompt, SessionId, config)
    B->>SS: dispatchFor(SessionId)
    SS-->>B: Fresh or Resume(wire id)
    B->>P: open and drain LiveTurn
    P-->>B: AgentResult(wire id, usage, output)
    B->>SS: commit session mapping
    B-->>A: AgentResult
    A-->>F: output
    F->>E: persist resume wire id
    S->>C: stage completion
    C->>C: persist StageEntry and commit changes
```

A `FlowSession` is the durable bridge: it uses an ordinary `Chat` for the
actual in-process conversation, but adds seed/preamble handling and persists
the resume wire id after each successful turn. In contrast, `agent.chat()` is
only an in-attempt `Chat`; it is suitable for parallel review fan-out but is
not resumable.

## 4. Review models

```mermaid
flowchart LR
    Catalog[ReviewerCatalog] --> Definition[Reviewer<br/>name, description, prompt, file pattern]
    Base[reviewAgent: Agent] --> Build[buildReviewers]
    Definition --> Build
    Build --> ReviewerAgent[ReviewerAgent<br/>definition + read-only Agent]
    ReviewerAgent --> Selector[ReviewerSelector]
    Selector --> Selected[Selected reviewers]
    Selected --> Fanout[reviewOnce / parallel review turns]
    Fanout --> Result[ReviewResult]
    Result --> Findings[List ReviewFinding]
    Findings --> Report[ReviewReport<br/>findings by reviewer]
    Report --> Loop[reviewAndFixLoop]
    Loop --> Fixer[FlowSession / coding agent]
    Fixer --> Loop
    Loop --> Open[OpenFindings]

    classDef readonly fill:#e6f0ff,stroke:#2f64a3,color:#222;
    class ReviewerAgent readonly;
    classDef state fill:#e6ffed,stroke:#167c3a,color:#222;
    class Open state;
```

`Reviewer` is a definition, while `ReviewerAgent` is the operational pairing of
that definition with a specifically named, prompted, read-only `Agent`. Review
turns produce `ReviewFinding` values; the loop aggregates them into
`OpenFindings`, asks a fixer to address them, then repeats up to its configured
limit.

## Source map

| Concern | Primary source files |
| --- | --- |
| Agent and ephemeral conversations | `tools/src/main/scala/orca/agents/{Agent,AgentCall,Chat,AgentConfig}.scala` |
| Backend turn/session SPI | `tools/src/main/scala/orca/backend/{AgentBackend,TurnRequest,LiveTurn,SessionSupport}.scala` |
| Flow capabilities and sessions | `flow/src/main/scala/orca/{FlowContext,FlowControl,Session,StageFrames}.scala` |
| Progress and run state | `flow/src/main/scala/orca/progress/ProgressLog.scala`, `flow/src/main/scala/orca/sessions/SessionRecord.scala` |
| Review domain | `flow/src/main/scala/orca/review/{ReviewLoop,Reviewers,ReviewFinding,ReviewReport}.scala` |

For lifecycle invariants and design rationale, read `adr/0018-stage-bound-flow-runtime.md` in the Orca checkout.
For its earlier architectural overview, see `adr/README.md` there; these ADRs are not part of Yallmo's documentation.
