# OpenAI-Compatible (OAI) Provider

`provider.oai` implements the OpenAI Chat Completions dialect. It is currently configured for Docker Model Runner, but
its protocol-level name reflects that the same adapter can target compatible servers such as Ollama, vLLM, and LM
Studio.

`OAILlmProvider` owns text generation and the HTTP completion transport. `OAIAgent` composes that provider to execute
one native agent turn or one audio-only transcription request; trusted orchestration remains in `AgentRunner`.

The `openai` package remains separate: `OpenAiCodexClient` uses the ChatGPT/Codex Responses API and subscription OAuth,
while `OpenAiTtsService` uses the official OpenAI Platform TTS API.

When adding another compatible backend, explicitly model and test any differences in tool calls, JSON response mode,
token-usage fields, audio content, and supported generation options rather than assuming complete compatibility.
