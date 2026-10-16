# HOW TO RUN

Run all commands from the repository root.

## Requirements

- A JDK compatible with Scala 3.9.
- [sbt](https://www.scala-sbt.org/).

## Verify the checkout

Compile and run the test suite:

```bash
sbt test
```

## Start the interactive REPL

The REPL needs a model endpoint and model name. Create the gitignored `.env` file:

```dotenv
MY_MODEL_URL=http://localhost:12434/engines/llama.cpp/v1
MY_MODEL_NAME=ai/gemma4:E4B
```

Then start the line-oriented client in a terminal:

```bash
sbt --server -no-server "repl/run --ui=line"
```

For non-interactive input, use `--ui=plain`; `--ui=auto` selects line mode only when a suitable terminal is available. See the [REPL guide](RELP.md) for commands, sessions, tools, voice input, privacy, and troubleshooting.

## Other demos

- The [local RAG demo](../RAG.md) explains its model download and run command.
- The [System One / Jev guide](system_one/System_One_Overview.md) documents its optional local smoke test.
