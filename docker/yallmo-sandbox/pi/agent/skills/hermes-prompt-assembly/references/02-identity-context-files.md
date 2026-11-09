# SOUL.md and Context Files

Source: https://hermes-agent.nousresearch.com/docs/developer-guide/prompt-assembly

## SOUL.md Identity

`~/.hermes/SOUL.md` is the Hermes identity file. When present, it replaces the hardcoded default identity block and becomes the first section of the system prompt.

Simplified behavior:

```python
def load_soul_md():
    soul_path = get_hermes_home() / "SOUL.md"
    if not soul_path.exists():
        return None
    content = soul_path.read_text().strip()
    content = scan_context_content(content, "SOUL.md")
    content = truncate_content(content, "SOUL.md")
    return content
```

If `SOUL.md` does not exist, Hermes falls back to `DEFAULT_AGENT_IDENTITY`.

When `skip_context_files` is set, such as during some subagent delegation flows, Hermes does not load `SOUL.md` and uses the default identity.

## Preventing Duplication

Because `SOUL.md` can also be considered a context file, Hermes uses `skip_soul=True` when building project context after loading it as identity. This prevents it from appearing twice.

## Project Context Priority

Hermes uses a first-match-wins priority system. Only one project context type is loaded:

1. `.hermes.md` or `HERMES.md`
   - Searches from cwd upward to git root.
   - Hermes-native project config.

2. `AGENTS.md`
   - Current working directory only at startup.
   - Subdirectory hints may be discovered progressively during the session.

3. `CLAUDE.md`
   - Current working directory only.

4. `.cursorrules` or `.cursor/rules/*.mdc`
   - Current working directory only.

## Context File Processing

All loaded context files are:

- security scanned for prompt injection patterns,
- truncated to a maximum size,
- frontmatter-stripped for `.hermes.md` where reserved for future config overrides.

Long files are truncated before injection.

## Design Rules

- Identity and project rules are separate concepts.
- Project context priority prevents conflicting instruction sources from piling up.
- Security scanning and truncation happen before prompt injection.
- First-match-wins reduces prompt bloat and conflicting repo instructions.
