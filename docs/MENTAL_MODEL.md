# Yallmo's mental model

Start here when deciding where code belongs:

```text
ApplicationRuntime → wires the application together
RuntimeInstances   → constructs and caches agents/providers
Agent              → prepares a model turn
AgentRunner        → controls the loop and executes tools
Tools              → perform specific operations
```

This is a responsibility map, not an inheritance hierarchy. In particular, an `Agent` handles **one turn**, not the
whole conversation. Most agents use a model; provider-free agents can complete a turn without one.

## Who does what?

| Component | Responsibility | Not its responsibility |
| --- | --- | --- |
| `ApplicationRuntime` | Connect configuration, selected agents, execution settings, tools and session services. | Perform inference or implement the agent loop. |
| `RuntimeInstances` | Lazily construct agents/providers, bind dependencies and reuse successful instances by ID. | Store conversations or execute tools. |
| `Agent` | Handle one turn: prepare/delegate a model request or perform provider-free work, then return a response. | Own the runner's multi-turn tool loop. |
| `AgentRunner` | Drive an agent program, enforce budgets and available capabilities, validate tool arguments and execute requested actions. | Implement provider HTTP protocols or persist session history. |
| Tools | Implement specific operations using their supplied dependencies, such as saving an application record. | Decide the overall conversation flow. |

Two supporting roles complete the picture:

- **Provider:** performs model inference through a particular backend/account. A provider connection can be shared by
  multiple agents; it does not own the application tool loop.
- **Orchestrator:** owns session/history operations and coordinates persist–run–persist around an execution.

## Setup versus execution

```text
SETUP
Configuration
    │
    ▼
ApplicationRuntime ──uses──► RuntimeInstances
    │                           │
    │                           └── initialized agents/providers
    ▼
Assembly: session services + tools + context
    │
    └── selected agent binding → submission service

EXECUTION
User message
    │
    ▼
Orchestrator records the message
    │
    ▼
AgentRunner
    ├── asks the agent program for its next decision
    │      └── native-model path: Agent.turn → provider → response
    ├── final answer? ───────────────────────────────────┐
    └── tool call? → validate → execute tool → next turn │
                                                       ▼
                                      Orchestrator records the result
```

The model may **propose** a tool call. Trusted application code decides whether it is available and executes it.
Agent-owned tools are bound to the selected agent; exposing a tool implementation does not automatically grant
permission to use it.

## Ownership and lifetime

In a configured standalone server:

```text
ServerApplication.Configured
    ├── ApplicationRuntime
    │      └── RuntimeInstances
    │             ├── cached agents
    │             └── cached providers
    └── Assembly
           ├── orchestrator / session storage
           └── shared tools and context
```

Connections and sessions reuse this application composition. Selecting another agent does not create another runtime.
ZIO scopes manage resource lifetime; the tree above describes ordinary object references, not a language-enforced
ownership system. Configuration is a snapshot: editing the file does not mutate existing instances automatically.

## Keep it simple

- Prefer clear responsibilities over increasingly generic infrastructure.
- Add an abstraction to solve a concrete problem, not merely to remove a small `match` or hide a dependency.
- Keep permission checks, workspace scoping and persistence boundaries explicit.
- Treat this map as the intended separation, not a claim that every current implementation is perfectly decoupled.

## Code and related documentation

- [ApplicationRuntime](../server/src/main/scala/fmgp/yallmo/server/application/ApplicationRuntime.scala)
- [RuntimeInstances](../core/jvm/src/main/scala/fmgp/yallmo/orchestration/RuntimeInstances.scala)
- [Agent](../model/shared/src/main/scala/fmgp/yallmo/model/Agent.scala)
- [AgentRunner](../core/shared/src/main/scala/fmgp/yallmo/orchestration/AgentRunner.scala)
- [Tool interfaces](../core/shared/src/main/scala/fmgp/yallmo/orchestration/Capabilities.scala)
- [Agent and provider configuration](CONFIG_AGENT_PROVIDER.md)
- [Capabilities architecture](Capabilities_Architecture.md)
