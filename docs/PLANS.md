# Plans and delivery status

Plans stay active while implementation, an in-scope defect, or an acceptance gate needs work.
Completed implementation/execution records live in [`completed/`](../completed/README.md).
Archiving a delivery does **not** certify manual acceptance or connected-client feature parity.
Current behavior belongs in the user and architecture guides, not historical task boards.

This review compared plan status with source and test coverage. Test counts in archived records are historical;
no Scala, browser, hardware or external-provider test suite was rerun for this documentation-only review.

## Active plans and proposals

| Plan | Disposition |
| --- | --- |
| [Managed history and message rebase](orchestration/MANAGED_HISTORY_AND_REBASE_PLAN.md) | Implementation and review through step 7 complete (narrowed step 5). Model/core validation passes without exclusions. Manual/live acceptance and qualified full-server/REPL failure disposition remain pending; plan stays active. |
| [Attachments and blob storage](orchestration/ATTACHMENTS_AND_BLOB_STORAGE_PLAN.md) | Implemented, **not ready to archive**: the documented failed-message-append bug remains in `core/jvm/src/main/scala/fmgp/yallmo/orchestration/OrchestratorLive.scala`. Move message append before producer finalization and add a permanent regression before closing the plan. |
| [Debug by message ID](transcriber/DEBUG_BY_MESSAGE_ID_PLAN.md) | Future work. `/debug message <hash>` is not implemented; connected `/debug request` is a different delivered feature. |
| [Research sub-agent and web search](../core/src/main/scala/fmgp/yallmo/tool/WEB_SEARCH_PLAN.md) | Proposed; the research child and search service/tool are not implemented. |
| [Research-child MCP support](../core/src/main/scala/fmgp/yallmo/tool/mcp/MCP_TOOL_SUPPORT_PLAN.md) | Proposed; MCP transport/adapter and research-child integration are not implemented. |
| [Pi runner](../core/src/main/scala/fmgp/yallmo/provider/pi/PI_DEV_PROVIDER_PLAN.md) | TODO placeholder, not a completed delivery. |
| [SSH remote workspace](tools/TOOL-SSH.md) | Proposed architecture; routed workspace/controller implementation remains open. |
| [System One in the REPL](system_one/SYSTEM_ONE_IN_REPL.md) | Reviewed proposal; `/so run` and the modal request builder are not implemented. |
| [System One orchestration](system_one/ORCHESTRATION_RESEARCH.md) | Research/proposal; the decision-model foundation exists, but the closed worker registry/selector is not delivered. |
| [OpenAI Decisions API](../model/shared/src/main/scala/fmgp/yallmo/model/som/Support-OpenAI-Decisions-API.md) | Research placeholder, not an implemented integration. |

## Implementation and research references kept in place

- [User identity model](future-plan/UserId-future_identity_model.md): implemented with explicit owned actors; authentication remains separate.

These are still useful reference documents, not active execution boards:

- [Scala script tool](tools/TOOL-SCALA-SCRIPT.md): implemented `ScalaTool`/`ScalaCodeExecutor` and matching suites. OS/container sandboxing is explicitly future work, not delivered protection.
- [Jev API research](system_one/JEV_API_RESEARCH.md): the initial `SOMProvider`/`JevHttpProvider`/`SystemOneDecisionAgent` foundation is implemented. Keep the research because routing and Laya experiments remain proposals; use the [System One overview](system_one/System_One_Overview.md) for today's API.
- [Scala Metals](tools/TOOL-SCALA-METALS.md): integration research, not a completed tool.

## Open follow-ups from archived deliveries

### Connected REPL integration

The client-only startup cutover and `/debug request` transport are delivered; see the
[archived plan and handoff](../completed/README.md#client-only-repl).
The [REPL guide](REPL.md) and [server guide](../server/README.md) own the current runtime/security contract.

- [ ] Integrate remote `/tools`, richer agent information, terminal provider creation/editing, voice/TTS, human interactions and remaining diagnostics (`/debug print|export`). Preserve the existing implementations/tests until parity is decided; do not restore a local startup fallback.
- [ ] Resolve retained legacy voice test failures. The archived request-debug handoff records **231/237 REPL tests passing, six voice failures**; this is historical evidence, not a new test result or a green full-suite claim.
- [ ] Perform a real-provider, separate-process terminal smoke test; fake-provider/socket coverage is not provider acceptance.
- [ ] Decide compatibility-code retirement only after feature-parity review. Authentication and run-management/status/cancellation APIs remain separate work; the current deployment requires trusted access.

### Voice/browser manual acceptance

The embedded browser-audio and transcriber implementations passed their recorded automated/review gates.
They are retained, but the client-only CLI does not enable them remotely. Their earlier passing suites do not
supersede the later voice failures above.

- [ ] Verify real JVM and browser microphones, intelligible configured-provider transcription, audible browser/OS TTS, and actual UI/storage/debug output.
- [ ] Verify cancellation, consecutive recordings, chat-agent switching, disabled voice and draft/cursor preservation without auto-submission.
- [ ] Complete the [browser manual acceptance checklist](../browser-STT-TTS/README.md#manual-acceptance-not-performed-by-node-tests): CSP/worklet loading, permissions/device changes, background/re-enable behavior, disconnection and optional SSH forwarding where used.

The old inline/redacted-audio design is superseded by [blob storage](orchestration/ATTACHMENTS_AND_BLOB_STORAGE_PLAN.md).
Recordings are retained as blobs; message hashes have no audio exception. Multi-step session/audio/transcript
retention is not transactional, and the separate blob append defect above remains open.

## Archived in this review

- Browser STT/TTS design and execution plans.
- Transcriber-as-agent design and execution plans.
- Client-only REPL startup plan and continuation handoff, including delivered request diagnostics.

See the [archive index](../completed/README.md) for delivery evidence and links. Historical checklists/logs are
preserved; source names, security assumptions, dependency versions and `/tmp` artifact paths there may be stale.
