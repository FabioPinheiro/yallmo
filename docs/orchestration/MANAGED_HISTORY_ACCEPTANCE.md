# Managed history integration acceptance (step 7)

Status: **steps 1–7 and head-identity simplification implemented and independently reviewed; final acceptance pending**.
Continuation-reset (P1) and submission-documentation (P2) findings are fixed and
re-reviewed. Automated model/core validation passes without source exclusions.
The plan stays active for live/manual gates and qualified full-server/REPL failures.

## Automated versus manual gates

- [x] Formerly excluded `StorageFSMessageMaybeSuite`: all four tests run through the
  supported partial-session API, including a real empty session, missing head and
  missing ancestor. No production access widening or source exclusion.
- [x] Generic fixed-schema request wire tests: complete Choice/Score/Noul envelopes,
  mixed schema, aliases, both optional Noul forms, supplied custom encoders,
  roundtrip/decode, malformed-custom-encoding rejection and recording typed provider.
  Helper policy/wire tests use normal question derivation, without a private workaround.
- [x] Managed-history regressions cover memory and filesystem/reopen, async journal
  barriers/retries, original identities/parents, complete tool pairing, reasoning
  sanitization, response audit, captured-head action/model/extension guards, different-head rejection and positive exact restoration, cancellation
  and capability expiry. System One advisory data does not become conversation data.
- [x] Two Promise-coordinated **complete submissions** overlap on the same session.
  One rebases/finishes while the other is suspended; the latter finishes afterward.
  Both preserve independent active histories, execution audits and immutable branches;
  the competing pointer survives and publication failure logs ERROR. No session lock.
- [x] Continuation-reset/rebase repair: a nonzero reset boundary, shortening rebase,
  newly generated reasoning and a complete tool loop reach the next actual native
  provider request in order. Old continuation replay stays filtered even with a changed
  summary, while canonical originals remain inspectable. A Promise-gated concurrent
  reused execution and subsequent reuse assert per-submission isolation/capabilities.
- [x] Normal server captures stable continuation exclusions at reset/switch and reuses
  them across later submissions after shortening; next-provider-request, switch,
  restart and filesystem inventory assertions pass. Immutable origin reads do not
  refresh stale-work fences. Public submit docs now distinguish execution audit from
  authorized active-history replacement.
- [x] Independent rereview of step 7 repairs, including initial review's P1/P2; no blocking findings.
- [ ] Manual graph inspection against a synthetic rewritten session.
- [ ] Explicitly opted-in live System One **and** conversational-provider run below.

See the [plan's step-7 record](MANAGED_HISTORY_AND_REBASE_PLAN.md)
for exact commands, counts and qualified full-server/REPL results. Environment skips
and broader failing tests are not live acceptance. No source exclusions or temporary
sbt settings are used for step 7.

## Head-identity follow-up (automated and independently reviewed)

The extra rewrite counter and advisory-only rebase API are removed. Local active head
`Option[MessageId]` is the sole work identity. Exact restoration A -> B -> A succeeds;
ordinary local appends change that identity and invalidate old work. Session pointer-only
competition does not. Atomic accepted append returns its own head; no unlocked reread
can bless stale queued actions. `initialMessages` remains immutable and non-refreshing;
only explicit program `messages` reads refresh its model-work head for rebuilt requests.

Promise-coordinated memory/FS tests cover advisory latency and atomic-apply gaps,
restored model/action contexts, stale model/extension/RAG output on original parents,
queued-action prevention, concurrent append mismatch and competing session pointers.
Normal two-tool and two-RAG batches advance through their own writes without false
staleness. Empty/root/no-op and closed-capability checks remain covered. See the
[follow-up outcome](MANAGED_HISTORY_AND_REBASE_PLAN.md#follow-up--message-head-guards-approved-in-progress)
for commands/counts. These are automated fixtures, not live or manual acceptance.

## Inspect original versus active history

Use the existing API and web inspector; no new UI is needed:

1. For a synthetic submission, retain its original IDs, managed final IDs and
   identifier-only rebase logs. Once writes drain, `OrchestratorLive.listMessages`
   contains originals, copies and any stale-output branches. Follow `parentId`, not
   timestamps, to reconstruct each chain. Filesystem reopen retains these objects.
2. `loadSession(id)` follows the published pointer. When publication succeeds, it
   equals the rewritten active chain. When CAS loses, it shows the competing chain:
   successful local context activation does **not** imply pointer publication.
   `SessionTurn.session` may be an older last acknowledged pointer; `newMessages`
   is accepted prompt plus actual execution, not rebase copies or current context.
3. In **All messages** / **Conversation graph**, inspect original and copy IDs,
   authors, payloads and parents. The graph uses the global message inventory,
   shares ancestors and limits display depth to 32. Use All messages for deeper
   nodes. The Sessions view is pointer-based, not an unpublished-branch selector.
4. The graph has ancestry edges, not durable original-to-copy metadata. Rebase
   mapping/removal provenance is log-only. There is no named-branch recovery UI,
   durable operation journal or automatic resumption of a losing branch. `fork`
   forks the published session; standalone `rebase` cannot publish an arbitrary
   inventory head. Do not promise recovery through either operation.

The managed suite asserts these object/pointer distinctions on memory and FS/reopen.
A human still needs to check the existing browser rendering; fixture assertions do
not certify manual UI acceptance.

## Opt-in synthetic-data live smoke — BLOCKED / PENDING

Prerequisites were absent at approval: `JEV_SYSTEM_ONE_URL` and `TYPESAFE_API_KEY`
were unset, with neither configured in `.env`. No live request was made. An absent
key is acceptable **only** for an explicitly chosen unauthenticated compatible
local endpoint, not as evidence that official Jev is configured. Never print
credential values/config files or reuse saved project conversations for this check.

This is a developer-driven composition using existing seams, not a new smoke
framework or a new REPL command. `/so` is still a proposal, not an entry point.

1. **Obtain explicit operator consent before sending anything.** The operator must
   configure a reachable Jev/compatible endpoint (`JEV_SYSTEM_ONE_URL`, and
   `TYPESAFE_API_KEY` for official Jev), its supported System One model, and an
   independently reachable conversational provider. For the existing unauthenticated
   OAI-compatible adapter, set `MY_MODEL_URL` (base URL, without `/chat/completions`)
   and `MY_MODEL_NAME`. `OAILlmProvider` does not add an API key: choose an endpoint
   it actually supports rather than changing login/account behavior. Do not start
   containers, authenticate, change SSH or assume localhost is a configured service.
2. In the existing embedding (or `sbt 'core/console'`), construct the real services
   with these **existing** layers. Construction/evaluation must be explicitly run
   by the operator; the following does not run during automated tests:

   ```scala
   import fmgp.yallmo.model.som.SOMProvider
   import fmgp.yallmo.provider.jev.JevHttpProvider
   import fmgp.yallmo.provider.oai.{OAILlmProvider, OAIAgent}
   import zio.http.Client

   val liveProviders = Client.default >>> (JevHttpProvider.live ++ OAILlmProvider.live)
   // In a ZIO effect supplied with liveProviders:
   // advisor <- ZIO.service[SOMProvider]
   // llm     <- ZIO.service[OAILlmProvider]
   // chat = OAIAgent.make(AgentId("smoke-assistant"), llm)
   // delegate = NativeFunctionAgent[Agent[Any]](chat.id, llm.defaultModel).bind[Any](chat)
   ```

3. Use a **new empty temporary filesystem store/session**, registered synthetic
   human and `chat` adapter via `RuntimeInstances.fromAgents`, `registerUser` and
   `OrchestratorLive.filesystem`. Seed only literal synthetic payloads:
   `User("Summarize the synthetic result")`,
   `Context("temporary-research", "Synthetic scratch calculation: 2 + 2 = 4")`,
   `Context("research-summary", "Verified synthetic result: four")`.
   No blobs, private message IDs/evidence, credentials or real tool side effects.
4. Use the [existing agent-program wrapper](../system_one/MANAGED_HISTORY_DECISIONS.md#usage-inside-an-agent-program)
   with the real bound `delegate`. Its `prepare` call must receive the real `advisor`
   via `provideEnvironment(ZEnvironment[SOMProvider](advisor))` (or equivalent layer
   provisioning). The selector chooses the temporary note as exclusive base, the
   accepted current head as inclusive head and the first request as destination;
   send only the literal bounded evidence from that example. Capture `history` for
   post-run inspection. Submit `AgentTask("Answer using the verified synthetic summary")`
   through `AgentRunner[Any](Map.empty, Map.empty).execution(program, context)` and
   `Orchestrator.submit`. No tools or default cleanup registration are necessary.
5. Observe the real advisory choice/confidence without logging raw provider bodies
   or evidence. KEEP is a legitimate policy outcome, **not proof of rebase**. If
   uncertain, record KEEP/inconclusive; do not fake a Rebase answer, weaken thresholds
   or label skipped evaluation as success. For a real REBASE, verify that the real
   conversational request after explicit history refresh omits the scratch note and
   retains the verified summary/current task. A local recording wrapper around the
   actual `OAIAgent.turn` may inspect only this synthetic request and then forward it;
   it must not substitute a fake response.
6. Check successful real continuation, execution audit with no advisory pollution,
   unchanged originals and copied branch via `listMessages`, correct published
   session chain after reopening the **temporary** store, and the graph distinction
   above if using the existing browser. Record endpoint kind/model names (no secrets),
   observed KEEP/REBASE, resulting IDs, successful continuation and any provider error.
   Record manual UI separately. If a service is unavailable, record BLOCKED/PENDING,
   not passed. Keep or remove only this temporary fixture under operator control.

The existing `core/Test/runMain fmgp.yallmo.provider.jev.MainJevHttpProvider` is an
optional **System One-only** synthetic manual main hardwired to
`host.docker.internal:8000`; it does not use the environment endpoint and does not
exercise managed history or a conversational provider. Running it alone cannot
satisfy the combined live gate. No live main was run for automated acceptance.

## Remaining limits

Inventory-wide ancestry scans, unbounded writer queue, stuck storage delaying final
drain, best-effort ordinary saves, partial immutable copy prefixes, log-only rewrite
provenance and no crash transaction/exactly-once guarantee remain. Trusted programs
must actually rebuild requests after explicit refresh; a read cannot prove provider
consumption. Complete-independent-pair cuts and conservative removal of all reasoning
may reject/omit otherwise usable context. Evidence selection/bounding stays caller
owned; there is no default cleanup, automatic retry detector, editing framework,
new session policy or live-provider acceptance claim.
