// Offline wire tests against the installed Pi SDK. No host auth files, provider calls or real tokens.
// Run: PI_TEST_PACKAGE_DIR="$(npm root -g)/@earendil-works/pi-coding-agent" node --test test-provider.mjs
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import test from "node:test";

const directory = path.join(path.dirname(fileURLToPath(import.meta.url)), "templates");
const packageDirectory = process.env.PI_TEST_PACKAGE_DIR || "/usr/local/lib/node_modules/@earendil-works/pi-coding-agent";
const { ModelRuntime } = await import(pathToFileURL(path.join(packageDirectory, "dist/index.js")).href);
const sentinel = "oai-oat01-proxy-managed";

async function configured(t) {
  const temporary = await mkdtemp(path.join(tmpdir(), "pi-sbx-wire-"));
  t.after(() => rm(temporary, { recursive: true, force: true }));
  const auth = JSON.parse(await readFile(path.join(directory, "auth.json"), "utf8"));
  assert.ok(JSON.stringify(auth) === JSON.stringify({ "sbx-openai": { type: "api_key", key: sentinel } }),
    "The auth fixture must contain only the public proxy sentinel; never copy a host auth.json here.");
  for (const name of ["auth.json", "models.json"]) {
    await writeFile(path.join(temporary, name), await readFile(path.join(directory, name)), { mode: 0o600 });
  }
  const runtime = await ModelRuntime.create({
    authPath: path.join(temporary, "auth.json"),
    modelsPath: path.join(temporary, "models.json"),
    modelsStorePath: path.join(temporary, "catalog.json"),
    allowModelNetwork: false,
    refreshOnCreate: false,
  });
  assert.equal(runtime.getError(), undefined);
  const model = runtime.getModel("sbx-openai", "gpt-6.1-sol");
  assert.ok(model);
  return { runtime, model };
}

function streamResponse(items) {
  const records = items.map((item) => `event: ${item.type}\ndata: ${JSON.stringify(item)}\n\n`).join("");
  return new Response(records, { status: 200, headers: { "Content-Type": "text/event-stream" } });
}

function events(item, delta) {
  const response = {
    id: "resp_mock", object: "response", model: "gpt-6.1-sol", status: "completed", output: [item],
    usage: { input_tokens: 3, output_tokens: 2, input_tokens_details: { cached_tokens: 0 },
      output_tokens_details: { reasoning_tokens: 0 } },
  };
  const start = { ...item, ...(item.type === "message" ? { content: [] } : { arguments: "" }) };
  const list = [
    { type: "response.created", response: { ...response, status: "in_progress", output: [] } },
    { type: "response.output_item.added", output_index: 0, item: start },
  ];
  if (item.type === "message") {
    list.push({ type: "response.content_part.added", output_index: 0, content_index: 0, item_id: item.id,
      part: { type: "output_text", text: "", annotations: [] } });
    list.push({ type: "response.output_text.delta", output_index: 0, content_index: 0, item_id: item.id, delta });
  } else {
    list.push({ type: "response.function_call_arguments.delta", output_index: 0, item_id: item.id, delta });
  }
  list.push({ type: "response.output_item.done", output_index: 0, item });
  list.push({ type: "response.completed", response });
  return list;
}

const greeting = { role: "user", content: "Hello", timestamp: 1 };
const answer = { type: "message", id: "msg_mock", role: "assistant", status: "completed",
  content: [{ type: "output_text", text: "Hello", annotations: [] }] };

function capture(requests, responseEvents) {
  return async (input, init) => {
    const request = new Request(input, init);
    assert.equal(request.url, "https://chatgpt.com/backend-api/codex/responses");
    assert.equal(request.headers.get("authorization"), `Bearer ${sentinel}`);
    assert.equal(request.headers.get("chatgpt-account-id"), null); // no synthetic JWT/account metadata
    const body = await request.json();
    assert.equal(body.store, false);
    assert.equal(body.stream, true);
    assert.equal(typeof body.instructions, "string");
    assert.ok(body.instructions.length > 0);
    assert.equal(body.max_output_tokens, undefined);
    assert.equal(body.prompt_cache_retention, undefined);
    requests.push(body);
    return streamResponse(responseEvents);
  };
}

test("Pi resolves auth.json's sentinel without OAuth login, refresh or JWT decoding", async (t) => {
  const { runtime, model } = await configured(t);
  assert.equal(model.api, "openai-responses");
  assert.ok((await runtime.getAuth(model)).auth.apiKey === sentinel, "Only the proxy sentinel may be resolved.");
  assert.equal(runtime.isUsingOAuth("sbx-openai"), false); // actual OAuth is exclusively the host proxy's job
});

test("text streaming sends only the sentinel and retains Pi's developer instructions", async (t) => {
  const { runtime, model } = await configured(t);
  const requests = [];
  const result = await runtime.streamSimple(model, { systemPrompt: "Keep answers concise.", messages: [greeting] }, {
    fetch: capture(requests, events(answer, "Hello")), maxRetries: 0, reasoning: "low",
  }).result();
  assert.equal(result.stopReason, "stop", result.errorMessage);
  assert.equal(result.content.filter((item) => item.type === "text").map((item) => item.text).join(""), "Hello");
  assert.equal(requests.length, 1);
  assert.match(JSON.stringify(requests[0].input), /Keep answers concise/);
});

test("function calls and results round-trip using the same sentinel-only provider", async (t) => {
  const { runtime, model } = await configured(t);
  const requests = [];
  const tools = [{ name: "echo", description: "Echo a message", parameters: {
    type: "object", properties: { message: { type: "string" } }, required: ["message"],
  } }];
  const call = { type: "function_call", id: "fc_mock", call_id: "call_mock", name: "echo", arguments: '{"message":"hi"}' };
  const first = await runtime.streamSimple(model, { messages: [greeting], tools }, {
    fetch: capture(requests, events(call, call.arguments)), maxRetries: 0, reasoning: "low",
  }).result();
  assert.equal(first.stopReason, "toolUse", first.errorMessage);
  const tool = first.content.find((item) => item.type === "toolCall");
  assert.equal(tool.name, "echo");
  assert.deepEqual(tool.arguments, { message: "hi" });
  const final = await runtime.streamSimple(model, { tools, messages: [greeting, first, {
    role: "toolResult", toolCallId: tool.id, toolName: "echo", content: [{ type: "text", text: "hi" }],
    isError: false, timestamp: 2,
  }] }, { fetch: capture(requests, events(answer, "Hello")), maxRetries: 0, reasoning: "low" }).result();
  assert.equal(final.stopReason, "stop", final.errorMessage);
  assert.ok(requests[1].input.some((item) => item.type === "function_call_output"));
});
