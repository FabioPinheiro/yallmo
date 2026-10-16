# HOW TO RUN

Run all commands from the repository root.

## Requirements

- A JDK compatible with Scala 3.9.
- [sbt](https://www.scala-sbt.org/).

## Verify the checkout

Compile and run the test suite:

```bash
sbt ';model/testOnly *;core/testOnly *;repl/testOnly *'
```

## Start the interactive REPL

By default, the REPL uses an OpenAI-compatible endpoint. Create the gitignored `.env` file:

```dotenv
MY_MODEL_URL=http://localhost:12434/engines/llama.cpp/v1
MY_MODEL_NAME=ai/gemma4:E4B
```

Then start the line-oriented client in a terminal:

```bash
sbt --server -no-server "repl/run --ui=line"
```

For non-interactive input, use `--ui=plain`; `--ui=auto` selects line mode only when a suitable terminal is available. See the [REPL guide](RELP.md) for commands, sessions, tools, voice input, privacy, and troubleshooting.

## Use a ChatGPT subscription instead

Start the existing REPL in subscription mode, then log in inside it (no credentials, OpenAI API key or local generation endpoint required at startup):

```bash
sbt --server -no-server 'repl/run --provider=chatgpt --ui=line'
```

At `yallmo>`, enter `/login` (browser, the default) or `/login device` (headless), follow its separate private prompt, then chat in the same REPL. `/login` does not switch an OAI-selected session to ChatGPT.

See the [subscription setup guide](OPENAI_SUBSCRIPTION.md) for browser/device login, model overrides, credential storage, account limits and the user-run smoke test. Voice transcription remains separately OAI-backed.

## Other demos

- The [local RAG demo](../RAG.md) explains its model download and run command.
- The [System One / Jev guide](system_one/System_One_Overview.md) documents its optional local smoke test.
