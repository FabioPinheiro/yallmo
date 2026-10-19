# Yallmo

**Yallmo** - *Yet Another Large Language Module Orchestrator* is an experimental Scala 3 and ZIO project for exploring the building blocks of LLM-agent applications.

## What is Yallmo?

Yallmo combines provider-neutral LLM models, structured agent turns, tool execution, local RAG, and a terminal REPL. Its central design is deliberately simple:

> A model may propose a response or a tool call; trusted application code decides what is available, validates arguments, executes approved work, and records the result.

This keeps the model useful as a planner without making it the authority for side effects.

## Goal

The first version of Yallmo extracted the LLM and TTS logic from my ASAR application (an augmented-reality adventure-story experience for travelers). The first version also added a simple REPL agent harness.

Yallmo is now a personal laboratory for learning how to manage context and knowledge in AI-agent systems, and how to orchestrate agent loops.

- Experimenting with Scala's new capture-checking feature, different technologies, and model types beyond LLMs—such as System One decision models.
- Orchestrating across multiple providers, including local ones.
- Defining code and architectural ownership between humans and agents.
- Capturing intent, requirements, and assumptions.
- Reviewing, measuring, and inspecting agent flows.

The project aims to keep the important boundaries small and explicit: transcripts, providers, capabilities, approval, orchestration, and storage. It is exploratory software; APIs and experiments may change.

## Documentation

- [Setup and running](docs/HOW_TO_RUN.md)
- [Interactive REPL](docs/REPL.md) — NORMAL `v`: JVM voice; `m`: browser microphone; `t`: explicitly speak the current assistant reply.
- [Browser audio setup, privacy and manual checks](browser-STT-TTS/README.md) — plain loopback page, no frontend installation.
- [Capabilities architecture](docs/Capabilities_Architecture.md) — configuration inheritance, restrictions, enforcement flow, and REPL voice policy.
- [ChatGPT subscription provider](docs/OPENAI_SUBSCRIPTION.md)
- [Local RAG demo](RAG.md)
- [System One / Jev decision protocol](docs/system_one/System_One_Overview.md)
- [Conversation-DAG visualization plan](docs/CONVERSATION_DAG_VISUALIZATION_PLAN.md)
- [Ideas and planned work](docs/TODO_AND_IDEAS.md)

## Project layout

- `model/` — provider-neutral models and orchestration state.
- `core/` — providers, orchestration, tools, and RAG.
- `repl/` — the interactive terminal client and lazy scoped browser audio bridge.
- `browser-STT-TTS/` — packaged browser microphone/speech page and dependency-free Node tests.


## WIP & TODO

### Newest Inference Engines

Docker Model Runner is no longer sufficient for the experiments I want to run. I want direct access to the latest **vLLM** and **llama.cpp** engines and APIs, without waiting for Docker Model Runner to expose their capabilities.

- **Incremental input and output:** stream text and audio chunks into supported backends and process them while more input is arriving; receive text and audio chunks incrementally. Streaming output alone is not enough. Actual support depends on the engine, model, and endpoint.
- **Realtime voice experiments:** try [Voxtral Mini 4B Realtime](https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602) through [vLLM's Realtime API](https://docs.vllm.ai/en/latest/serving/online_serving/speech_to_text/#realtime-api) for incremental speech-to-text. Conversation and speech synthesis remain separate capabilities, composed as needed.
- **System One models:** experiment with constrained decision models alongside conversational models, keeping their contracts separate. Run **Laya in a custom Docker container** and integrate it through the [System One / Jev boundary](docs/system_one/System_One_Overview.md), rather than treating it as a chat-completion model.
