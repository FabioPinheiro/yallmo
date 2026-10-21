# OpenAI-Compatible (OAI) Provider

`provider.oai` implements the OpenAI Chat Completions dialect. It is currently configured for Docker Model Runner, but
its protocol-level name reflects that the same adapter can target compatible servers such as Ollama, vLLM, and LM
Studio.

`OAILlmProvider` owns text/prompt-audio inference and the HTTP completion transport. `OAIAgent` composes that provider
for one native agent turn, which may include audio; the REPL applies its own single-turn transcription policy. Trusted
conversational orchestration remains in `AgentRunner`.

Audio is `MessagePayload.AudioAttachment(blobId, format, description)`. Adapters resolve `AgentTurnRequest.readBlob`
(or the prompt-audio API's reader), reject missing/empty/over-1-MiB content, and base64-encode bytes only for HTTP.
Direct callers must supply a reader; `Orchestrator.turn` and REPL runner bindings supply Storage's reader automatically.
Request diagnostics contain references/format, not the exact audio wire body. Blob ingestion is buffered, not provider
audio streaming. See [Attachments and blob storage](../../../../../../../../docs/orchestration/ATTACHMENTS_AND_BLOB_STORAGE_PLAN.md).

The `openai` package remains separate: `OpenAiCodexClient` uses the ChatGPT/Codex Responses API and subscription OAuth,
while `OpenAiTtsService` uses the official OpenAI Platform TTS API.

When adding another compatible backend, explicitly model and test any differences in tool calls, JSON response mode,
token-usage fields, audio content, and supported generation options rather than assuming complete compatibility.
