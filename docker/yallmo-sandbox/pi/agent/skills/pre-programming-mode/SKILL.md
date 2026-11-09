---
name: pre-programming-mode
description: "A collaborative pre-implementation planning mode. Use only when the user explicitly enables or invokes this skill; do not auto-load for normal coding tasks."
disable-model-invocation: true
---

# Pre-Programming Mode

Use this skill only when explicitly enabled by the user, for example with `/skill:pre-programming-mode`, by pasting this skill into the conversation, or by a direct request to enter pre-programming mode. Once explicitly invoked, treat pre-programming mode as active for the current conversation until the user disables it.

## Purpose

Pre-programming mode separates **thinking and decision-making** from **writing code**. The assistant remains responsible for the eventual implementation, but first works with the user to clarify goals, identify meaningful architectural or design choices, and ask for decisions when trade-offs matter.

This technique is based on collaborative software design practices such as:

- **Requirements clarification before implementation**: reduce rework by confirming intent, constraints, non-goals, and success criteria.
- **Architecture Decision Records (ADR) thinking**: make important choices explicit, compare options, and record why one path was selected.
- **Design review before coding**: discuss structure, boundaries, data flow, naming, APIs, dependencies, and migration risks before committing changes.
- **Progressive commitment**: make reversible low-cost choices quickly, but pause for high-impact or hard-to-reverse choices.
- **Human-in-the-loop development**: the assistant implements, but the user owns product direction and important trade-offs.
- **Senior-developer decision ownership**: when the user is acting as the senior developer, important architecture, API, persistence, compatibility, and naming decisions must be surfaced to them before implementation.

## Core Behavior

When this skill is active:

1. **Do not immediately code** unless the user already approved a concrete plan or the task is trivial.
2. **Research the existing project first** when relevant: inspect files, conventions, architecture, tests, build tools, and nearby code.
3. **Restate the goal** in concise terms and call out assumptions.
4. **Identify decision points** before implementation.
5. **Ask the senior developer/user about important decisions**, especially when multiple reasonable options exist or when the choice affects architecture, public APIs, persistence, compatibility, naming, or future maintenance.
6. **Do not silently choose for consequential decisions** just because one option seems reasonable; present the decision, give a recommendation, and wait for confirmation.
7. **Offer options with trade-offs** instead of asking open-ended questions without context.
8. **Recommend a default** when useful, but wait for user approval for consequential choices.
9. **After agreement, implement the code yourself** using the approved direction.
10. **If new decisions arise while coding**, pause and ask again when they are material.
11. **Keep the process lightweight**: do not over-plan obvious or low-risk changes.

## What Counts as a Decision Point

Pause and discuss when a choice affects one or more of:

- Project/module/package structure
- Public APIs, routes, CLI commands, schemas, or data models
- Persistence format, migrations, compatibility, or data loss risk
- Error handling strategy and user-facing behavior
- Dependency choice or introducing a new framework/library
- Security, privacy, authentication, authorization, or secrets
- Performance-sensitive architecture or concurrency model
- Testing strategy for non-trivial behavior
- Naming of important domain concepts
- UX/product behavior where intent is ambiguous

Do **not** pause for every minor implementation detail. Choose sensible defaults for local, reversible, idiomatic decisions.

When in doubt, ask one focused senior-developer question rather than making a broad hidden design decision.

## Suggested Workflow

### 1. Understand

- Summarize the requested outcome.
- Inspect the relevant codebase/documents when needed.
- List constraints, unknowns, and non-goals.

### 2. Discover Options

For each meaningful decision, present a compact comparison:

```markdown
Decision: <what must be decided>

Option A: <name>
- Pros: ...
- Cons: ...
- Best when: ...

Option B: <name>
- Pros: ...
- Cons: ...
- Best when: ...

Recommendation: <option> because <reason>.
Question: Should we proceed with <option>, or do you prefer another direction?
```

### 3. Agree on a Plan

Before coding, produce a short implementation plan:

```markdown
Planned implementation:
1. ...
2. ...
3. ...

Decisions confirmed:
- ...

Validation:
- ...
```

Ask for approval if decisions are consequential. If no senior-developer decision is needed, say that explicitly and explain why the change is local/reversible/trivial.

### 4. Implement

Once approved:

- Make the code changes.
- Follow project conventions.
- Keep changes aligned with the agreed plan.
- If the plan becomes wrong, stop and explain the new decision.

### 5. Report

At the end, summarize:

- Files changed
- Decisions applied
- Validation run or not run
- Remaining follow-ups

## Question Style

Prefer focused, answerable questions:

Good:

> The new feature can live either in the existing `billing` module or a new `subscriptions` module. I recommend `subscriptions` because the concepts are diverging. Do you want that split now, or should we keep it inside `billing` for a smaller change?

Avoid:

> What architecture do you want?

## Default Stance

- The assistant is responsible for implementation quality.
- The user is responsible for approving important product and architecture decisions.
- The assistant should treat the user as the senior developer when they indicate that role, and should ask them focused questions before making consequential technical choices.
- The assistant should reduce cognitive load by researching, narrowing options, and recommending a path.
