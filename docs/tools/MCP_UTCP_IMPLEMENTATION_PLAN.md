# MCP / UTCP — architecture review and implementation roadmap

Status: **proposal, not implemented**. This is an architecture-level review of the project and the relevant execution paths, not a line-by-line audit or a new full-suite validation.

## Recommendation

Implement **HTTP-only external-tool integration**: MCP over **Streamable HTTP**, followed by a deliberately small **UTCP HTTP/JSON profile**. Adapt both into the existing tool/function execution boundary; do not introduce a second agent loop or permission system.

**Decision: no MCP client stdio support**, now or as a planned fallback. Yallmo connects to already-running, explicitly configured HTTP endpoints; it does not launch MCP server processes. Reject stdio/command configurations, including ones nested inside UTCP MCP call templates. Legacy MCP HTTP+SSE and WebSocket transports are also outside the selected profile. SSE responses used by Streamable HTTP are required and are not the excluded legacy transport.

UTCP can provide an MCP integration through its MCP plugin, but implementing only UTCP's ordinary HTTP templates does **not** implement MCP. Evaluate a UTCP-backed MCP adapter versus a direct MCP HTTP client in Phase 0; do not assume either gives compatibility with every HTTP MCP server.

Keep three independent decisions separate:

1. **Integration:** how Yallmo discovers and invokes an external capability (MCP or UTCP).
2. **Authorization and exposure:** which agent can use it, which calls need approval, and how much metadata reaches the model.
3. **Publication:** whether another application can call Yallmo as an MCP server. This is optional later work, not required for either client integration.

The research child remains a good first product use case, but neither that child nor mission/workflow execution should be a prerequisite for testing the external-tool integration.

## 1. Project review: what exists and what is missing

The build uses Scala 3.10.1, ZIO 2.1.27, zio-json 1.1.3 and JVM zio-http 3.11.6. Preserve the JVM/Scala.js split.

| Area | Current evidence | Consequence for MCP / UTCP |
| --- | --- | --- |
| `model/` | `AgentFunction`, grouped `AgentTool`, `FunctionToolDefinition`, `FunctionCall`, `StaticContext`, provider-neutral schemas | Reuse these contracts; external integrations are tools, not LLM providers. |
| `core/` | `AgentRunner` authorizes against effective context, validates inputs, executes implementations and records correlated results | Adapt external calls here rather than bypassing the runner. Validate again inside the adapter before any approval/network work. |
| Configuration and catalog | `AgentCapabilities` restricts tools/functions; `AgentCatalog` caches complete executable generations | Discovery must produce a coherent, immutable selected catalog. A server appearing later must not silently broaden an agent's permissions. |
| Startup composition | `ServerApplication` owns lifetime; `ApplicationTools` currently has a fixed built-in catalog | Add explicit external-tool composition, not a global mutable append to the built-ins. |
| Approval | `FunctionApproval.guard` exists but is not installed automatically | Attach reviewed policy explicitly. The wrapper itself does not validate inputs; the external adapter must enforce validation-before-approval even for direct callers. |
| JSON Schema | `JsonSchema` supports primitive types, string enums, arrays and simple objects | General external schemas are not representable today. There is no union/null/ref/constraint support. This is an early compatibility gate, not a transport detail. |
| Results | Generic `ExternalFunctionResult` and persisted generic function outputs carry text | Start with bounded text/JSON envelopes. Rich MCP images/audio/resources need an explicit later result/blob/provider design. |
| `protocol/` and `rpc/` | Application JSON-RPC/WebSocket wire API and separate typed capability RPC | Neither implements MCP. Do not change their semantics or introduce a core → protocol/server dependency to reuse envelope classes. |
| `scalacode-api/`, compiler/linker | Reviewed snippet APIs and invocation-scoped capability bindings exist | Do not grant generic external-tool access to snippets automatically. A later Scalamode bridge must re-enter the same validation, authorization, approval and budget path. |
| Server, REPL and web harness | Server-owned execution; connected REPL is client-only; website has its own auth/session boundary | Connections and secrets belong on the host, not in browser code or a newly embedded terminal runtime. Share sanitized diagnostics through the application API later. |
| Filesystem, storage and RAG | Existing rooted capabilities and independent storage/RAG mechanisms | Remote metadata must not obtain raw filesystem/storage access. Do not conflate MCP resources with automatic RAG ingestion. |
| Research and missions | Research/search plans are unimplemented; working-tree `mission/` is a structural draft | Prove a harmless external function through an ordinary configured agent first; integrate a bounded child when its runtime exists. |
| Docker / Pi | Sandbox installs Playwright MCP and configures it for **Pi** | This does not give Yallmo an MCP client. Existing broad Pi exposure is not Yallmo's intended default policy. |
| UTCP | At the initial review, existing material was a README research question, with no implementation | This document now owns the combined plan. Establish a pinned interoperability profile and fixtures before claiming UTCP support. |

### Integration principles

This document is the single MCP/UTCP roadmap. The separate research-child MCP plan has been removed; its useful authorization and untrusted-data principles remain here, without a subprocess transport dependency.

- General integration must not depend on research orchestration. Research-only exposure is a profile, not a transport-layer rule.
- Use `AgentFunction` / `ExternalFunctionResult`, `FunctionApproval`, `ApplicationTools` and explicit tool membership.
- Implement Streamable HTTP correctly: initialization, `notifications/initialized`, bounded paginated `tools/list`, protocol-version checks, capability negotiation, JSON/SSE responses and session handling.
- A client must handle protocol control traffic (for example ping) and explicitly reject unsupported requests. Excluding sampling/roots/elicitation is not permission to leave unsolicited requests hanging.
- Connection recovery must not automatically replay `tools/call`: after a disconnect the external side effect may already have happened.
- Dynamic list changes and cached agent generations need an explicit policy; do not mutate an in-flight `StaticContext`.
- Use provider-compatible function names rather than dotted external identifiers.

Two project-specific integration hazards need explicit acceptance tests:

1. **Eager validation:** `ServerApplication.make` validates every configured agent against startup definitions. Adding external tools only during later `toolWirings` cannot by itself make their explicit IDs pass startup validation.
2. **Trusted wiring additions:** `ToolWiringResolver` filters before wiring, then validates final output without a general permission re-filter. Never append an entire discovered external catalog there assuming a later guard will narrow it. Bind an explicitly authorized external profile.

## 2. What current Pi contributes

Reviewed the live [pi.dev](https://pi.dev/) page, installed Pi **1.6.1** MCP/codemode docs, and current upstream MCP documentation. Older descriptions of Pi as having “no MCP” are now historical: the website explicitly says MCP + Codemode are built in.

Useful design lessons:

- Pi supports both stdio and Streamable HTTP; Yallmo deliberately selects only Streamable HTTP. Legacy HTTP+SSE is not the same as SSE responses within Streamable HTTP.
- Separate tool exposure from connection configuration. Pi supports `direct`, `deferred`, `codemode` and `hidden`, including per-tool overrides.
- Large catalogs need progressive discovery rather than injecting every schema into every request.
- MCP invocations, including codemode calls, pass through the normal tool pipeline and permission hooks.
- Keep connection state, bounded diagnostics, per-server disable/reconnect and credential handling visible to the operator.
- Treat reconnection separately from retrying execution; Pi explicitly does not retry tool calls merely because transport failed.
- Pi codemode can reduce/filter tool results before returning them to the model. Its QuickJS environment has no ambient Node/filesystem/network APIs.

**Do not copy blindly:** Pi's configuration command substitutions, broad exposure defaults or all of its OAuth/resource machinery are not MVP requirements. Yallmo should start with explicitly granted external tools and host-resolved secret references, not shell evaluation in configuration. Scalamode is useful composition inspiration, but JVM/Node snippets are not equivalent to Pi's QuickJS sandbox.

The Pi runner placeholder concerns calling Pi as a separate harness. It is not needed for native MCP/UTCP support. No built-in UTCP support was established by the Pi sources reviewed.

## 3. MCP and UTCP: complementary adapters

| Question | MCP | UTCP |
| --- | --- | --- |
| Main integration shape | Negotiated JSON-RPC connection to an MCP server | A tool manual describes tools and protocol-specific call templates; the client invokes the described service |
| Main value here | Use existing tools such as Metals and Playwright | Call existing HTTP APIs without requiring a dedicated MCP wrapper for each API |
| Cost | Lifecycle, HTTP session management, transport and capability compatibility | Template interpretation, parameter binding, multiple protocol profiles and compatibility choices |
| Security burden | Network authority, untrusted schemas/descriptions/results, credentials | The same data risks plus destination/method/header metadata; reject command/process templates |
| Initial Yallmo slice | Tools over Streamable HTTP only | Pinned manual format plus HTTP/JSON; MCP templates only if backed by the approved MCP HTTP implementation |

UTCP is not “MCP but stateless,” nor does it remove authentication or sandboxing needs. Its Python implementation includes an MCP plugin, so the two are not mutually exclusive. “No wrapper server” is useful when an existing API is directly callable; it is not a universal performance or security guarantee.

The reviewed UTCP Python documentation uses `manual_version`, `utcp_version`, `tools`, `inputs`, `outputs` and `tool_call_template`, with `call_template_type`. Other/older implementation examples use provider terminology. Pin a tested revision and manual/template profile rather than treating all examples as one stable wire contract.

### Can UTCP use any HTTP MCP server?

**UTCP can use HTTP MCP servers through an MCP-aware plugin; compatibility with every server is not guaranteed.** The reviewed Python `utcp-mcp` plugin documents HTTP connections, tool discovery, sessions and OAuth2. Its `mcp` call template configures an MCP client, which delegates connections to `mcp-use` and the MCP SDK. This is MCP support beneath a UTCP API, not a replacement of MCP by generic HTTP requests.

Distinguish three configurations:

| Configuration | Meaning |
| --- | --- |
| UTCP `call_template_type: "http"` | Discover a UTCP manual or call a described ordinary HTTP API. It does not automatically perform the MCP handshake, sessions or `tools/call`. |
| UTCP `call_template_type: "mcp"` with an HTTP server | Use an MCP-aware adapter/plugin to discover and invoke MCP tools. No UTCP manual endpoint needs to be added to the existing MCP server. |
| Yallmo's approved subset | Streamable HTTP MCP tools, explicit grants and supported schemas/results. Reject all process transports even if an upstream plugin supports them. |

Compatibility still depends on the selected plugin/SDK version, actual HTTP transport, MCP revision, authentication flow, client capabilities requested by the server, schemas and result types. A README's `transport: "http"` label alone is not proof of the required Streamable HTTP behavior. Servers requiring sampling, roots, elicitation or other excluded features are not automatically usable; a server that also offers those features may still have independently usable tools. OAuth2 client-credentials support is not evidence of the complete MCP authorization/discovery/interactive-login flow.

Two possible internal implementations share the same Yallmo tool boundary:

1. **Direct MCP HTTP client plus restricted UTCP HTTP interpreter** — the initial baseline, with an explicit Scala/JVM SDK choice.
2. **UTCP facade with HTTP and MCP-HTTP adapters** — viable if its implementation meets the same policy, error/result fidelity, cancellation and dependency requirements. MCP lifecycle still exists underneath.

Do not implement two competing MCP stacks. If UTCP MCP templates are accepted later, route them to the selected MCP HTTP client. Adopting the existing Python plugin would require an explicit runtime/bridge decision; UTCP's protocol does not make that Python package a native Scala dependency. Validate preservation of MCP `isError`, structured/multimodal content, pagination and safe logging rather than assuming the generic UTCP result is lossless.

## 4. Target architecture

```text
Trusted configuration + secret references
                  │
       ServerApplication scope
                  │
       External connection/catalog services
           ├── MCP client: Streamable HTTP only
           └── UTCP client: manual + HTTP/JSON templates
               └── optional MCP template → same MCP HTTP client
                  │
       approved descriptors + bound AgentFunctions
                  │
       selected ResolvedAgent / StaticContext
                  │
       AgentRunner → adapter validation → approval → external call
                  │
       bounded normalized result + safe provenance
```

Start with small protocol-specific clients and a shared descriptor/policy/result boundary. Avoid building a general plugin platform or forcing MCP sessions and ordinary HTTP calls into one transport abstraction.

### Ownership and placement

- `core/shared/.../tool/mcp` and `.../utcp`: pure wire models/conversion only where useful to both targets.
- `core/jvm/.../tool/mcp`: scoped Streamable HTTP client, session lifecycle, adapters; no MCP process launcher.
- `core/jvm/.../tool/utcp`: manual loader, restricted HTTP-template interpreter, adapters.
- Shared external policy/result helpers in core only when both integrations actually need them; do not add speculative public APIs to model.
- `server/.../application`: load trusted configuration/secrets, acquire clients, compose authorized profiles and publish sanitized availability.
- `protocol/`, REPL and web harness: small diagnostic DTOs/views later, never credentials or model-selected endpoint controls.
- No new sbt module initially. Revisit extraction only after a reusable client boundary exists.

### Catalog and capability rules

- Default: no external tools without an explicit grant, even where omitted native capability fields normally inherit application availability.
- Preserve separate `ToolKey` groups and `FunctionKey` operations with exact declared membership. Prefix matching is not authorization.
- Map external names to stable provider-safe IDs (for example `mcp__docs__search`, `utcp__catalog__lookup`), with deterministic collision/length handling and an exact reverse mapping.
- For the first slice, perform bounded discovery during opt-in scoped startup **before** building/validating the selected catalog. Required configured profiles fail explicitly on discovery failure. `AgentCatalog.resolve` remains local; normal startup without external configuration remains unchanged.
- Freeze descriptors/schema fingerprints per generation. Notifications can mark a profile stale/unavailable; refresh requires an explicit new generation. Withdrawn or changed tools must fail closed at dispatch, not acquire changed authority under an old schema.
- Keep schemas and implementations paired. Reconnection must check identity/schema continuity before old adapters become callable again.
- Scope stateful browser sessions and credentials by agent/profile/principal. Application ownership of a manager does not imply sharing one browser or authentication context among unrelated agents.

### Schema and result rules

- Preserve raw external descriptors for bounded host inspection; do not silently discard validation keywords to fit `JsonSchema`.
- Initially accept a documented, semantics-preserving schema subset and show explicit rejection reasons. Account for JSON Schema defaults, including omitted `additionalProperties`.
- Inventory schemas from an independently hosted HTTP MCP server before committing to the subset. Metals/Playwright are candidates only if their deployment exposes the supported HTTP transport; Yallmo does not launch them. If a useful first profile requires nullable values, unions, refs or constraints, approve a focused schema/validator/provider-mapping extension before advertising those tools. A harmless fixture alone is not ecosystem compatibility.
- Reuse text persistence initially with a documented JSON envelope: success/tool-error status, text, optional structured JSON, truncation and safe provenance. Do not label MCP `isError: true` as success.
- Distinguish protocol failures, tool execution errors and **unknown execution outcome** after disconnect/timeout. Preserve correlation with the Yallmo call ID without exposing credentials or raw remote errors indiscriminately.
- Explicitly reject/report unsupported binary content; do not silently drop it or fetch resource links. Images/audio and durable full-output artifacts require separate retention/blob decisions. Existing blob-append follow-ups make this unsuitable as an implicit MVP dependency.

### Security and lifecycle rules

- Remote origins/methods and host-side secret references are trusted configuration, never model-selected connection parameters. Require HTTPS except for explicitly configured loopback HTTP fixture/local-service profiles. Reject MCP `command`/`args`/`cwd`/process-environment configuration and non-HTTP transports before any SDK/plugin initialization, including nested UTCP entries; no fallback process launch.
- Treat descriptions, server instructions, annotations, manuals and outputs as untrusted. Do not install server instructions as a system prompt; server `readOnlyHint`/`idempotentHint` values do not grant permission.
- Enforce absolute call/startup/shutdown deadlines, output/frame/page/depth bounds, pending-request and concurrency limits. Progress may update diagnostics, not extend the hard run budget indefinitely.
- Cancel individual work without tearing down unrelated calls on a shared connection. Close invocation-owned HTTP streams and release/terminate owned MCP sessions on scope exit; do not promise that remote cancellation undoes effects.
- Reconnect with bounded backoff, but never automatically replay an ambiguously completed execution. A future retry policy needs trusted operation semantics or an idempotency mechanism.
- UTCP HTTP starts with fixed approved origins, methods and path templates; encoded argument binding, no model-controlled scheme/host/auth headers, no shell execution and no arbitrary plugins.
- Revalidate manual-provided destinations, DNS/address policy and redirects; block private/link-local/metadata targets unless an explicit local-service profile permits them. Keep credentials scoped to their origin and out of redirects to other origins. Remote manuals cannot authorize local access.

## 5. Delivery phases and acceptance gates

### Phase 0 — agree contracts and compatibility

- Approve client-first scope, default-deny profiles, ID mapping, schema subset and text/JSON result format.
- Capture bounded representative descriptors from one independently hosted Streamable HTTP MCP server and one UTCP HTTP service; verify candidate Metals/Playwright deployments actually provide the selected transport.
- Pin MCP protocol revision(s) and the UTCP manual/template profile. The reviewed MCP baseline is `2025-11-25`; do not claim all revisions.
- Compare a small native Scala/ZIO client, a maintained JVM MCP SDK and a UTCP-backed MCP-HTTP adapter. Check actual Streamable HTTP behavior, lifecycle, schema/result preservation, cancellation, authentication, logging, dependency weight and Scala.js isolation before choosing one MCP implementation. A Node/Python bridge adds packaging and authority boundaries; it is not the default merely because SDKs exist there.
- Verify that rejected stdio/command templates cannot trigger subprocess creation through a transitive SDK/plugin. No process-launch integration belongs in the selected MCP profile.

**Gate:** agreed compatibility matrix and an integration design for eager capability validation, without changing unrelated orchestration.

### Phase 1 — MCP Streamable HTTP vertical slice

Connect to a configured endpoint and implement initialize → initialized notification → bounded paginated list → allowlisted function → validated call → normalized result. Support JSON **and SSE** POST responses, protocol/session headers, session expiration, request correlation, control messages, cancellation and bounded session/stream cleanup from the first slice. An optional GET stream can follow where required by the selected compatibility profile; do not confuse it with legacy HTTP+SSE.

Start with configured HTTPS and a host-side bearer-token reference; allow explicitly configured loopback HTTP for deterministic fixtures. Exercise a harmless local HTTP fixture through the ordinary `AgentRunner`, with no real LLM or research runtime required. Add failure-path tests rather than only codec tests.

**Gate:** authorized calls work; denied/invalid calls never reach the server; malformed/oversized output, disconnects, wrong/expired sessions, timeout and cancellation clean up without hanging. Both JSON and SSE response modes work. Stdio/command configuration is rejected before any launch. No external tools leak into other agents. No external credentials/internet required in CI.

### Phase 2 — useful profile and operational diagnostics

Integrate one reviewed read-only profile over an already-running Streamable HTTP endpoint. Metals or restricted Playwright qualify only if the independent deployment supports that transport. Yallmo does not spawn them or ship an automatic stdio-to-HTTP bridge. Start with no browser evaluation, uploads, downloads, authenticated state or arbitrary clicks/forms by default. Navigation still needs destination/egress policy; “read-only” does not mean no external effects.

Report connected, unavailable, denied, unsupported-schema and stale states. Introduce explicit reconnect/refresh without silently modifying running generations. Measure tool-schema tokens, returned bytes, latency and failures. Reuse the research child profile when that child is implemented; keep it isolated from the main agent.

**Gate:** one real HTTP-server smoke test, comparison with an independent MCP client and manual operator inspection, separate from offline CI. No broad capability widening or hidden transport bridge to make the demo work.

### Phase 3 — UTCP HTTP vertical slice

Load a trusted local manual or approved HTTPS manual endpoint using the pinned profile. Validate and allowlist descriptors, interpret only HTTP/JSON templates, and bind ordinary `AgentFunction`s through the same policy/result boundary as MCP.

Defer automatic OpenAPI conversion, CLI, generic streaming templates and arbitrary plugins. If Phase 0 selects support for UTCP `mcp` templates, bind them to the **same** approved MCP Streamable HTTP client rather than adding a second stack. Otherwise reject them explicitly as unsupported; ordinary UTCP HTTP support must not be presented as MCP interoperability. Never accept process transports inside a manual.

**Gate:** one fixture service works through both a Yallmo adapter and a pinned reference UTCP client; tests cover parameter encoding, unsupported template rejection, credential isolation and blocked destinations/redirects. If MCP templates are enabled, compare discovery/results against direct MCP access and prove nested process configurations are rejected.

### Phase 4 — broader HTTP authentication and interoperability, when needed

Add standards-compliant MCP authorization discovery, OAuth/login and token lifecycle only against a defined server requirement. Test actual grant types and server flows rather than treating a plugin's generic OAuth2 support as complete MCP authorization support. Keep manual-fetch, ordinary API and MCP credentials isolated by origin/profile.

Do not add legacy SSE, WebSocket or stdio for compatibility. Session recovery does not imply replaying the failed execution. Servers outside the supported HTTP/auth/capability profile remain visibly unsupported.

**Gate:** deterministic auth-failure/expiry tests and one consented real-server flow with disposable credentials; no secret-bearing diagnostics.

### Phase 5 — context-efficient discovery and Scalamode composition

Only after the direct slices work, add policy-filtered search/describe and optionally a narrow Scalamode invocation service. Every inner call must re-enter validation/approval/audit and consume explicit child-call budgets. A gateway call must not hide unlimited actions from the runner's action counter.

Yallmo's `StaticContext` is fixed for a run. Pi-style deferred declarations therefore require a deliberate runner/generation design; they cannot be added by just copying Pi's configuration strings. Start with small static profiles, then compare a bounded gateway against explicit next-run catalog refresh.

**Gate:** demonstrate reduced context size without discoverability of denied tools, approval bypass or unbounded hidden calls.

### Phase 6 — optional Yallmo MCP server

Only if external clients need Yallmo capabilities, add a separate server adapter over a narrow application execution API. Begin with a harmless `hello`/inspection fixture, not arbitrary Bash/Scalamode or the complete admin API.

Define client identity, per-tool authorization, admission/rate limits, cancellation/outcome semantics and audit first. Existing website cookies/CSRF/origin checks are not an MCP bearer/OAuth contract. Avoid unrestricted session/history/configuration exposure.

**Gate:** independent MCP client compatibility plus explicit negative authorization tests. This phase can use the public playground, subject to the constraints below.

## 6. Testing and the supplied playground

[The supplied MCP test client](https://mcpplaygroundonline.com/mcp-test-client) is a potential **manual interoperability aid**, not a CI dependency or a protocol conformance authority.

Access could not be verified here: browser navigation failed certificate validation, and a separate HTTP fetch returned 403. Its current transports, auth support, proxy behavior, localhost reachability and data retention remain unverified.

Important directionality:

- A test **client** can exercise an MCP **server**. It does not directly test Yallmo's MCP client by connecting two clients together.
- Before server mode exists, use a local fixture server plus an independent reference client (for example Pi or MCP Inspector) to compare discovery and harmless results with Yallmo.
- If the website supports Streamable HTTP, it may later test a deliberately isolated Yallmo MCP server. Only HTTP endpoints are in scope; no local process/stdio bridge is part of this plan.
- If it makes browser-side requests, CORS/preflight, HTTPS mixed-content and browser private-network rules matter. If it proxies requests, endpoint reachability and disclosure to that proxy matter instead. Verify which model it uses.
- Use synthetic data and disposable narrowly scoped credentials only. Do not paste production bearer tokens or publish the ordinary Yallmo host through a tunnel. Any CORS allowance must be exact and intentional, not `*` with credentials.

Keep the automated suite small and layered:

1. Pure codec/schema/name-mapping tests and malformed input cases.
2. Deterministic local HTTP/manual fixtures: JSON/SSE responses, pagination, interleaved messages, wrong IDs, session expiry, limits, cancellation, disconnect and ambiguous completion. Explicit rejection tests cover stdio/command templates, including nested UTCP configurations.
3. Real runner/catalog policy tests: startup validation, denied call count zero, direct-adapter validation-before-approval, generation/schema continuity, cross-agent isolation, secret-free diagnostics.
4. Opt-in real-server and reference-client smoke checks. Record these independently of offline tests and playground results.

No runtime changes or new test results are implied by this review. A full `sbt test` was not run for this documentation-only proposal; existing project follow-ups remain open.

## 7. Confirmed scope and remaining implementation decisions

Confirmed: **no MCP client stdio support**. The remaining implementation choices follow these recommended defaults:

- **Client first; publication later.** Do not implement an MCP server merely to use a testing website.
- **HTTP only: MCP Streamable HTTP → useful HTTP profile → UTCP HTTP/JSON.** No MCP stdio client, subprocess launch or automatic bridge. UTCP can move ahead of MCP if the first concrete integration is a REST API.
- **UTCP may front MCP, not replace its protocol.** Choose one MCP HTTP implementation in Phase 0. Accept UTCP MCP templates only through that implementation and its explicit compatibility profile; do not promise support for every HTTP MCP server.
- **General adapters, explicitly scoped consumers.** Keep the research-only policy as a profile without making research/mission orchestration an integration dependency.
- **Small static profiles first.** Defer dynamic discovery/codemode until measurement justifies the additional runner work.
- **Schema compatibility before breadth.** Reject unsupported descriptors visibly; expand the schema model only for demonstrated needs.
- **No broad new framework or transport bridge initially.** Make the SDK/native decision in Phase 0.

The first implementation task should be **Phase 0 plus one harmless MCP Streamable HTTP vertical slice**, not simultaneous implementation of multiple transports, every UTCP plugin, research orchestration and server mode.

## Sources and review boundaries

Project evidence: [mental model](../MENTAL_MODEL.md), [service wiring](../SERVICE_WIRING.md), [capabilities](../Capabilities_Architecture.md), the source files identified above, `build.sbt`, the search proposal and draft mission README. The earlier separate MCP proposal was reviewed and removed in favor of this combined plan. Current source takes precedence over older implementation-history notes.

External sources reviewed:

- [Pi website](https://pi.dev/); [upstream MCP guide](https://github.com/earendil-works/pi/blob/d4760131efe0740b9b7f7ad1f126734b96a011c0/packages/coding-agent/docs/mcp.md); installed Pi 1.6.1 `docs/mcp.md` and `docs/codemode.md`.
- MCP `2025-11-25` [transports](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/50145009cb63c9363962408fcdb0758adcadc87b/docs/specification/2025-11-25/basic/transports.mdx), [lifecycle](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/50145009cb63c9363962408fcdb0758adcadc87b/docs/specification/2025-11-25/basic/lifecycle.mdx), [tools](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/50145009cb63c9363962408fcdb0758adcadc87b/docs/specification/2025-11-25/server/tools.mdx), fetched from official repository sources because the rendered specification site returned 403.
- UTCP Python [README/manual format](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/README.md), [HTTP plugin](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/plugins/communication_protocols/http/README.md) and manual model. Reference behavior/documentation is not a claim of an audited Scala-compatible SDK.
- UTCP [MCP plugin guide](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/plugins/communication_protocols/mcp/README.md), [call-template model](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/plugins/communication_protocols/mcp/src/utcp_mcp/mcp_call_template.py), [implementation](https://github.com/universal-tool-calling-protocol/python-utcp/blob/bb8e2f6f6f78f4f773ff383a4e065523b0b1297a/plugins/communication_protocols/mcp/src/utcp_mcp/mcp_communication_protocol.py) and package metadata (`utcp-mcp` 1.2.0 at the reviewed revision). These establish the plugin integration path, not tested compatibility with all MCP servers or an approved Python bridge for Yallmo.

The Pi “Why we changed our minds” and Pi Durable articles returned 403; no conclusions here depend on their unseen contents. The playground was not successfully exercised. No MCP/UTCP tools, credentials, child agents or remote services were installed or launched for this review.
