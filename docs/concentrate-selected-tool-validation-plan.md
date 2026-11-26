# Agent prompt: Concentrate selected-tool validation

## Task

Improve the locality and depth of selected-tool validation in Yallmo without changing authorization behavior or validation timing. Investigate, implement the smallest justified refactor, and validate it.

This is an ownership refactor, not a permission redesign or a request for a generic catalog framework. Do not commit unless explicitly requested.

## Context

The architecture review is in `docs/architecture-review-tmp/architecture-review-20261125T232048Z.html`, candidate 01. Its source inspection used HEAD `642dfd0` plus the working tree; verify the current implementation before acting.

Two validation timings currently carry different declaration-availability knowledge:

- `ServerApplication.make` eagerly validates every configured agent against startup-available definitions. It explicitly supplements that catalog with Coach-private declarations by checking the agent type.
- `AgentCatalog.resolve` lazily constructs a complete executable selection and delegates selected-tool composition to `ToolWiringResolver`.
- `ToolWiringResolver` validates base membership, filters before trusted wiring, validates the returned catalog, and checks explicit IDs against accumulated base/final IDs.

The friction is duplicated catalog knowledge: application composition understands private declarations even though selected-tool resolution already owns substantial catalog rules. Concentrate that knowledge within the existing tool-selection module, while retaining eager validation and lazy binding as distinct timings.

This is not a confirmed execution defect. In particular, a non-Coach configured-path failure for later wiring IDs was inferred from source, not reproduced. Do not invent a new agent kind or speculative extension framework to justify the work.

## Read first

Follow applicable project instructions. Load the `codebase-design`, `scala-developer`, `zio-proficient-developer`, and `yallmo-glossary` skills where available.

Read:

- `GLOSSARY.md`, if present; otherwise `yallmo-storage/skills/yallmo-glossary/SKILL.md`.
- Relevant records in `docs/adr/`, if present.
- `docs/SERVICE_WIRING.md`.
- `docs/SERVICE_TOOLS_WIRING.md`.
- `docs/Capabilities_Architecture.md`.
- The current-contract sections of `docs/AGENT_TOOL_WIRING_PLAN.md`; distinguish historical plan text from current implementation.

Use the architecture vocabulary consistently: module, interface, depth, seam, adapter, leverage, and locality. Profiles and memory are planned concepts, not requirements for this work. Skills are immutable runtime snapshots until restart.

## Starting points

### Implementation

- `server/src/main/scala/fmgp/yallmo/server/application/ServerApplication.scala`
  - Review startup context construction, the Coach-specific supplement, eager validation, and the subsequent storage opening.
- `core/jvm/src/main/scala/fmgp/yallmo/orchestration/AgentCatalog.scala`
  - Review lazy complete-selection construction and generation caching.
- `core/shared/src/main/scala/fmgp/yallmo/orchestration/ToolWiringResolver.scala`
  - Review base/final catalogs, membership checks, declared policies, and skill-reader narrowing.
- `core/shared/src/main/scala/fmgp/yallmo/orchestration/config/AgentCapabilities.scala`
  - Review ID validation separately from restriction.
- Coach's declaration and wiring owners, following references from those files.

### Existing tests

Locate and inspect:

- `ToolWiringSuite`: local IDs, trusted additions, denied base IDs, malformed output, and declaration/implementation alignment.
- `AgentCapabilitiesSuite` and `AgentCatalogSuite`.
- `ServerApplicationSuite`: unavailable IDs fail before orchestration storage opens.
- `ServerSubmissionSuite`: selected private functions are visible through inspection.
- Relevant skill-activation and selected-tool composition suites.

The review inspected selected test bodies and names but did not run Scala tests. Do not treat its findings as a validation receipt.

## Behavior to preserve

1. Eager validation covers every configured agent, including unselected agents.
2. Invalid capability configuration fails before orchestration storage opens.
3. Executable-agent construction remains lazy; successful complete selections remain cached per generation.
4. Declaration validation does not trigger inference, remote authentication, private-tool execution, or eager private-root initialization.
5. Base tools are filtered before trusted wiring.
6. Wiring output is structurally validated without introducing a general post-wiring permission filter.
7. Explicit policy IDs are checked against available base/final IDs; denied IDs are not confused with nonexistent IDs.
8. Tool declarations, selected function definitions, and executable implementations remain aligned, including replacement schemas.
9. The skill reader retains its exceptional authorization, advertisement, and narrowing rules.
10. Lower-level request and execution guards remain independent and intact.

Do not discover every wired function at startup by executing arbitrary wiring. Do not broaden permissions to make configuration pass. Do not defer all validation until selection: the existing decision documents explicitly retain eager validation.

## High-level plan

### 1. Establish the current contract

Trace declaration availability from startup through selected-agent resolution, inspection, direct invocation, and managed execution.

Identify which facts are duplicated, which checks intentionally happen at different times, and what the existing tests protect. Add focused characterization coverage where needed.

Apply the deletion test: deleting the resolver would redistribute real validation complexity, so deepen it rather than replace it with pass-through indirection. Target duplicated caller knowledge, not useful checks.

### 2. Choose the smallest deepening

Identify where declaration-availability knowledge should live within the existing tool-selection module. Keep private declarations associated with their owner and retain both validation timings.

Before editing, briefly explain:

- What application composition will no longer need to know.
- What coordination the existing module will absorb.
- How this increases locality, depth, and leverage.
- Why this is more than moving a conditional behind a shallow forwarding interface.

No particular class, method, or type shape is prescribed. Do not add an interchangeable adapter seam when nothing actually varies across it. Keep core independent of server composition.

If preserving the guarantees requires substantial new machinery or a behavioral change, stop and ask for a decision rather than silently changing the contract. A justified conclusion that no small refactor earns its complexity is acceptable.

### 3. Implement narrowly

Remove duplicated catalog knowledge from application composition and give both validation timings coherent declaration ownership.

Avoid new agent hierarchies, mutable global catalogs, discovery frameworks, speculative agent kinds, and unrelated Scalamode or UTPC changes.

Inspect `git status` first and preserve existing dirty, staged, and untracked work. Do not modify the mission draft or reformat unrelated files.

### 4. Verify through the interface

The interface is the test surface. Retain or add observable configured-path tests showing:

- Valid explicit Coach-private tool/function IDs pass startup and selected resolution.
- Unknown or unavailable IDs fail, including on unselected agents.
- Invalid startup configuration does not open orchestration storage.
- Declaration validation does not eagerly initialize or execute agents, providers, or private tools.
- Inspection, direct invocation, and managed execution retain matching selected definitions and implementations.
- Trusted additions, denied base IDs, malformed wiring output, and skill-reader restrictions retain their semantics.
- Resolution still runs once per successful cached generation and respects existing invalidation behavior.

Reuse recording or fake dependencies already present in the tests. Prefer testing the real composition path over adding helper-only tests. Do not delete independent security regression coverage merely because checks appear similar.

### 5. Validate and document

Inspect `build.sbt` for current project identifiers and run the narrowest relevant Scala suites. Format only changed Scala files, then rerun affected validation and check the diff for unrelated changes.

Update architectural documentation only where ownership descriptions change. Do not rewrite decisions that remain valid. Do not introduce new domain terminology unnecessarily; if a genuinely new concept is needed, follow the domain-modeling skill before documenting it.

## Completion report

Provide:

- A concise explanation of the ownership change and its locality/leverage benefit.
- Changed file paths.
- Tests added or retained, commands run, and actual validation results.
- Any compatibility risks, unresolved decisions, or skipped validation.
- Confirmation that eager validation, lazy resolution, and authorization semantics remain unchanged.

Do not claim a general solution for arbitrary wiring-produced startup IDs unless the implemented contract and tests establish it.
