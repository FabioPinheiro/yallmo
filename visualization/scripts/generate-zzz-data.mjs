import { mkdir, readdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const visualizerRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const storageRoot = resolve(visualizerRoot, "..");
const zzzRoot = resolve(storageRoot, "zzz");
const outputPath = resolve(visualizerRoot, "src/generated/zzz-conversation.json");
const maxPreviewChars = 280;

const preview = (value) => {
  const text = String(value ?? "").replace(/\s+/g, " ").trim();
  return text.length > maxPreviewChars ? `${text.slice(0, maxPreviewChars - 1)}…` : text;
};

const contentFor = (payload) => {
  const [payloadType, value] = Object.entries(payload)[0] ?? ["Unknown", {}];
  switch (payloadType) {
    case "User":
    case "Assistant":
      return value.text ?? "";
    case "Reasoning":
      return value.summary ?? "Reasoning continuation is redacted.";
    case "FunctionCallRequest":
      return `${value.call?.name ?? "unknown"} ${value.call?.argumentsJson ?? "{}"}`;
    case "FunctionCallOutput":
      return value.output?.output ?? "";
    case "ToolResult":
      return value.text ?? "";
    case "RagResult":
      return (value.documents ?? []).map((document) => document.text ?? JSON.stringify(document)).join("\n\n");
    case "Context":
      return value.content ?? value.text ?? JSON.stringify(value);
    default:
      return JSON.stringify(value);
  }
};

const kindFor = (payload) => {
  const payloadType = Object.keys(payload)[0];
  return {
    User: "user",
    Assistant: "assistant",
    Reasoning: "reasoning",
    RagResult: "rag_result",
    FunctionCallRequest: "function_call",
    FunctionCallOutput: "function_result",
    ToolResult: "tool_result",
    Context: "context",
  }[payloadType] ?? "unknown";
};

const toNodeAndDetail = (artifact) => {
  const { id, value } = artifact;
  const payload = value.payload ?? {};
  const payloadType = Object.keys(payload)[0] ?? "Unknown";
  const payloadValue = payload[payloadType] ?? {};
  const content = contentFor(payload);
  const kind = kindFor(payload);
  const call = payloadValue.call;
  const output = payloadValue.output;
  const toolName = call?.name ?? output?.name ?? payloadValue.toolKey ?? payloadValue.retrieverKey ?? null;
  const callId = call?.id ?? output?.callId ?? null;
  const documentCount = Array.isArray(payloadValue.documents) ? payloadValue.documents.length : null;
  const metadata = {
    payloadType,
    toolName,
    callId,
    documentCount,
    redacted: payloadType === "Reasoning",
  };

  return {
    node: {
      id,
      parentIds: value.parentId ? [value.parentId] : [],
      timestamp: value.timestamp,
      agentId: value.agentId,
      kind,
      preview: preview(content),
      toolName,
      callId,
      documentCount,
    },
    detail: {
      id,
      kind,
      timestamp: value.timestamp,
      agentId: value.agentId,
      content,
      metadata,
      ...(payloadType === "Reasoning" ? { continuationExists: Boolean(payloadValue.continuation) } : {}),
    },
  };
};

const objects = (await readFile(resolve(zzzRoot, "objects.jsonl"), "utf8"))
  .split("\n")
  .filter(Boolean)
  .map((line) => JSON.parse(line))
  .filter((artifact) => artifact.kind === "msg");
const artifacts = new Map(objects.map((artifact) => [artifact.id, artifact]));

const lineageFor = (head) => {
  const lineage = [];
  const visited = new Set();
  let current = head;
  while (current && !visited.has(current)) {
    visited.add(current);
    const artifact = artifacts.get(current);
    if (!artifact) break;
    lineage.push(artifact);
    current = artifact.value.parentId;
  }
  return lineage.reverse();
};

const sessionDirectory = resolve(zzzRoot, "refs/sessions");
const sessionIds = (await readdir(sessionDirectory)).sort();
const sessionRefs = await Promise.all(
  sessionIds.map(async (id) => [id, JSON.parse(await readFile(resolve(sessionDirectory, id), "utf8"))]),
);
const lineages = new Map(sessionRefs.map(([id, ref]) => [id, lineageFor(ref.head)]));

const rootFor = (id) => {
  const lineage = lineages.get(id) ?? [];
  return lineage[0]?.id ?? null;
};

const graphFor = (selectedSession, scope) => {
  const selectedRef = sessionRefs.find(([id]) => id === selectedSession)?.[1];
  const relatedSessions = scope === "family"
    ? sessionRefs.filter(([id]) => rootFor(id) === rootFor(selectedSession))
    : sessionRefs.filter(([id]) => id === selectedSession);
  const selectedArtifacts = scope === "family"
    ? [...new Map(relatedSessions.flatMap(([id]) => (lineages.get(id) ?? []).map((artifact) => [artifact.id, artifact]))).values()]
    : lineages.get(selectedSession) ?? [];
  const nodeDetails = selectedArtifacts.map(toNodeAndDetail);
  const nodeIds = new Set(nodeDetails.map(({ node }) => node.id));
  const functionCalls = new Map(
    selectedArtifacts.flatMap((artifact) => {
      const payload = artifact.value.payload ?? {};
      const call = payload.FunctionCallRequest?.call;
      return call?.id ? [[call.id, artifact.id]] : [];
    }),
  );
  const edges = nodeDetails.flatMap(({ node }) => {
    const parentEdges = node.parentIds
      .filter((parentId) => nodeIds.has(parentId))
      .map((parentId) => ({ id: `parent:${parentId}:${node.id}`, source: parentId, target: node.id, kind: "parent" }));
    const output = artifacts.get(node.id)?.value.payload?.FunctionCallOutput?.output;
    const source = output?.callId ? functionCalls.get(output.callId) : undefined;
    const toolEdge = source && nodeIds.has(source)
      ? [{ id: `tool-result-for:${source}:${node.id}`, source, target: node.id, kind: "tool-result-for" }]
      : [];
    return [...parentEdges, ...toolEdge];
  });

  return {
    selectedSession,
    scope,
    nodes: nodeDetails.map(({ node }) => node),
    edges,
    heads: relatedSessions
      .filter(([, ref]) => nodeIds.has(ref.head))
      .map(([sessionId, ref]) => ({ sessionId, nodeId: ref.head })),
    truncated: false,
  };
};

const sessions = sessionRefs.map(([id, ref]) => {
  const lineage = lineages.get(id) ?? [];
  return {
    id,
    messageCount: lineage.length,
    headId: ref.head ?? null,
    agentId: lineage.at(-1)?.value.agentId ?? ref.agentIds?.at(-1) ?? null,
  };
});
const details = Object.fromEntries(objects.map((artifact) => [artifact.id, toNodeAndDetail(artifact).detail]));
const graphs = Object.fromEntries(
  sessionIds.map((id) => [id, { lineage: graphFor(id, "lineage"), family: graphFor(id, "family") }]),
);

await mkdir(dirname(outputPath), { recursive: true });
await writeFile(outputPath, `${JSON.stringify({ sessions, graphs, details }, null, 2)}\n`);
console.log(`Generated ${outputPath} from ${zzzRoot} (${objects.length} messages, ${sessions.length} sessions).`);
