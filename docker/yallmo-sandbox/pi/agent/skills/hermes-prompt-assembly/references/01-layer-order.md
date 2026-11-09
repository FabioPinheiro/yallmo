# Hermes Cached System Prompt Layer Order

Source: https://hermes-agent.nousresearch.com/docs/developer-guide/prompt-assembly

Hermes assembles a cached system prompt in a deliberate order. The order matters because earlier layers define identity and behavior, while later layers add contextual facts and operating constraints.

## Approximate Layer Order

1. **Agent identity**
   - `SOUL.md` from `HERMES_HOME` when available.
   - Otherwise `DEFAULT_AGENT_IDENTITY` from `prompt_builder.py`.

2. **Tool-aware behavior guidance**
   - Guidance about persistent memory, session search, and tool usage.
   - GPT/Codex-specific tool-use enforcement may be included.

3. **Honcho static block**
   - Included when active.

4. **Optional system message**
   - Config/API-provided system text.

5. **Frozen MEMORY snapshot**
   - Durable facts loaded at session/system-prompt build time.

6. **Frozen USER profile snapshot**
   - User profile loaded at session/system-prompt build time.

7. **Skills index**
   - Compact index of available skills and instructions to load relevant skills.

8. **Context files**
   - Project instructions such as `.hermes.md`, `HERMES.md`, `AGENTS.md`, `CLAUDE.md`, `.cursorrules`, `.cursor/rules/*.mdc`.
   - `SOUL.md` is not duplicated here if already used as identity.

9. **Timestamp / optional session ID**

10. **Platform hint**
   - Example: CLI-focused output guidance.

## Design Meaning

The cached prompt is the stable base of a conversation. It should change only when necessary because provider-side prompt caching depends on stable prefixes.

## Porting Guidance

When adapting this design to Pi or another harness:

- Identify which content is stable for the whole session.
- Put stable identity, tools guidance, memory policy/snapshot, skills index, and repo context in a stable prefix.
- Keep dynamic per-turn warnings, temporary recall, and gateway overlays outside the stable system prompt.
- Avoid rebuilding the system prompt after every memory write unless the architecture explicitly supports cache invalidation.
