# Scala Metals

This explores a specialised Scala agent. A System One decision can decide whether
Metals-derived project knowledge is useful; a normal agent/tool call then obtains
that knowledge from Metals.

## Metals and Git

See [Metals Reimagined — LSP in the age of LLMs](https://www.youtube.com/watch?v=VyM_MzFNHsI).

## What information is available through Metals?

**Recommendation:** use Metals' MCP server for an agent integration rather than
implementing its raw LSP protocol. MCP exposes the useful Scala-aware operations
as named tools, and can run either alongside an editor or headlessly through
`metals-mcp`. Raw LSP is still appropriate when building an editor integration.

| Area | Information available | Agent-facing operation(s) | Important limits |
| --- | --- | --- | --- |
| Symbols and API understanding | Matching symbols, their kind and fully qualified name; members, constructors, method signatures, ScalaDoc, references/usages, and source for a symbol | `glob-search`, `typed-glob-search`, `inspect`, `get-docs`, `get-usages`, `get-source` | Search is case-insensitive by the final name segment, results are capped (100 by default), and dependency matches are limited. This is semantic/indexed information, not an unrestricted code-search or AST dump. |
| Compiler feedback | Diagnostics for a file, its containing module, or the whole workspace | `compile-file`, `compile-module`, `compile-full` | Depends on a correctly imported build and compiler/index readiness. It reports compiler results, not a proof of runtime behaviour. |
| Build and dependencies | Build targets/modules; dependency coordinates and versions; build re-import | `list-modules`, `find-dep`, `import-build` | The build tool/BSP server remains the source of truth. Re-import after changing `build.sbt` or other build files. |
| Tests | Run suites, classes, or individual test methods and return results | `test` | Framework/build-server support is required; Metals documents support for ScalaTest, MUnit, ZIO Test, and others. A test run has normal execution cost and side effects. |
| Safe-ish mechanical changes | Scalafmt output and available/generated Scalafix transformations | `format-file`, `list-scalafix-rules`, `generate-scalafix-rule`, `run-scalafix-rule` | These can modify working-tree files. Generate/review a rule and present its diff before accepting it as an autonomous change. |
| Editor semantic data (raw LSP) | Open-buffer text, diagnostics, go-to-definition, references, document symbols, hover, and formatting | Standard LSP requests such as `textDocument/definition`, `references`, `documentSymbol`, `hover`, and `formatting` | Requires LSP lifecycle and document synchronization. This is the route for unsaved editor buffers, not the simplest headless-agent API. |
| Editor-only integrations | Debug/test UI integration, project tree, status, quick pick/input, and Doctor/build-health UI | Metals LSP extensions and Debug Adapter Protocol | These are client/UI protocols, not durable knowledge retrieval APIs. Use MCP tools when the agent needs facts. |

### Information Metals does *not* provide

Metals is not a Git provider: it does not expose commit history, branches,
diffs, blame, remotes, or PR/issue information. Obtain those from Git/GitHub
tools. It also does not replace ordinary filesystem access for arbitrary file
contents, nor does its public MCP surface promise raw compiler typed trees,
complete dependency graphs, or every editor code action.

### Proposed agent policy

1. Let System One decide *whether* Scala semantic context is needed (for
   example: `need_symbol_lookup`, `need_compile_check`, `need_test`, or
   `no_metals`). Do not use it to fabricate facts returned by Metals.
2. For an unfamiliar API, search a narrow symbol name, then `inspect` and
   `get-docs`; fetch source only when necessary.
3. Compile the changed file/module after an edit; use a full compile or tests
   only when warranted. Re-import immediately after build-definition changes.
4. Treat `test`, formatter, Scalafix, build import, and dependency resolution as
   potentially costly or mutating operations. Require the usual execution/write
   policy and keep the MCP endpoint local.

### Sources

- [Metals MCP documentation and tool reference](https://scalameta.org/metals/docs/features/mcp/)
- [Metals new-editor/LSP integration reference](https://scalameta.org/metals/docs/integrations/new-editor/)
- [Metals code-action catalogue](https://scalameta.org/metals/docs/features/codeactions/)
- [Metals GitHub source](https://github.com/scalameta/metals)
