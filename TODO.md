
## TODO & IDEAS

- [Add metadata to Blob and Preview](docs/future-plan/Blob-metadata-and-media-preview.md)


### Follow-ups from retired plans

Implemented plan records now live in `lixo/completed/`; moving them does not close these items.

- [ ] **Blob append failure:** in `core/jvm/src/main/scala/fmgp/yallmo/orchestration/OrchestratorLive.scala`, append the message successfully before capturing the producer exit/finalizing its buffer. Add a permanent regression proving failed message persistence never submits an empty blob or consumes its ID; preserve partial bytes when production fails after a successful append.
- [ ] **Managed history acceptance:** manually inspect a synthetic rewritten session in the graph; run the opt-in synthetic-data live System One + conversational-provider smoke only with explicit consent; disposition qualified full-server/REPL failures. Automated validation is not live acceptance.
- [ ] **SDLC evolution (deferred):** exercise the shared tools/HTTP inspector before deciding on editable/history views, project selection, retention/automatic maintenance, audit attribution, structured approvals, intent lifecycle or stricter containment. None is required to re-open the delivered initial implementation.

### TODO other

- [ ] In the server module move the website related files (WebAssets) into a package 'website'
- [ ] rename OrchestratorServerMain to MainServer
- [ ] rename model/shared/src/main/scala/fmgp/yallmo/model/tool to model/shared/src/main/scala/fmgp/yallmo/tool
- [ ] rename model/shared/src/main/scala/fmgp/yallmo/model/FunctionCall.scala to model/shared/src/main/scala/fmgp/yallmo/tool/FunctionCall.scala
- [ ] Authentication, cancellation/status APIs and richer run-management machinery.
- [ ] Review test and check all test that extends FunSuite or `Unsafe.unsafe { unsafe ?=> ... }` and use munit-zio lib!
- [ ] TerminalVoiceModeStatus.remoteConnection Remove!
- [ ] Rename package fmgp.yallmo.view.web to package fmgp.yallmo.webharness
- [ ] Review ServerApplication graceful-shutdown See https://zio.dev/reference/core/zioapp#graceful-shutdown-timeout
- [ ] [Local RAG demo](RAG.md)

### TODO - Agent Comands

Each agent should bring their own comments to the REPL.

### TODO - Newest Inference Engines

Docker Model Runner is no longer sufficient for the experiments I want to run. I want direct access to the latest **vLLM** and **llama.cpp** engines and APIs, without waiting for Docker Model Runner to expose their capabilities.

- **Incremental input and output:** stream text and audio chunks into supported backends and process them while more input is arriving; receive text and audio chunks incrementally. Streaming output alone is not enough. Actual support depends on the engine, model, and endpoint.
- **Realtime voice experiments:** try [Voxtral Mini 4B Realtime](https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602) through [vLLM's Realtime API](https://docs.vllm.ai/en/latest/serving/online_serving/speech_to_text/#realtime-api) for incremental speech-to-text. Conversation and speech synthesis remain separate capabilities, composed as needed.
- **System One models:** experiment with constrained decision models alongside conversational models, keeping their contracts separate. Run **Laya in a custom Docker container** and integrate it through the [System One / Jev boundary](docs/system_one/System_One_Overview.md), rather than treating it as a chat-completion model.

### IDEA - /btw

Add a command that entres a conversation mode. Where its not allowed to use most of the tool. Specially, is not allowed to modify anything.
It can still have submode to search the files in the FS and search the Internet
When exiting this mode a summary will be written back into the context of the other.

The idea is to have a conversation, discuss ideas and understanding the options.

### IDEA - agents can share tools/functions with other agents

We have use capture-checking to make sure those tools don't space the resquet

### CLEANUP

- deprecate the browser-STT-TTS and the voice in REPL
- Old implemented plans moved to `lixo/completed/`; unfinished proposals remain in `docs/PLANS.md`.