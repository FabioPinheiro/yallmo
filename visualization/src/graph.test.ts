import { describe, expect, it } from "vitest";
import { colorsByAgent, createFlowGraph, relatedNodeIds } from "./graph";
import { storageAllSessionsGraph, storageGraph, storageSessions } from "./storage-snapshot";
import type { ConversationGraph } from "./api";

const sessionId = storageSessions[0]?.id;
if (!sessionId) throw new Error("The zzz snapshot contains no sessions.");
const graph = storageGraph(sessionId, "family");

describe("conversation graph mapping", () => {
  it("combines all session lineages into one graph", () => {
    const allSessions = storageAllSessionsGraph();

    expect(allSessions.heads).toHaveLength(storageSessions.length);
    expect(new Set(allSessions.nodes.map((node) => node.id)).size).toBe(allSessions.nodes.length);
    expect(new Set(allSessions.edges.map((edge) => edge.id)).size).toBe(allSessions.edges.length);
  });

  it("assigns each agent in a graph a distinct color", () => {
    const colors = colorsByAgent(graph.nodes);

    expect(new Set(colors.values()).size).toBe(colors.size);
  });

  it("maps the actual zzz tool call/result relation as a semantic edge", async () => {
    const { edges } = await createFlowGraph(
      graph,
      new Set(["user", "assistant", "reasoning", "rag_result", "function_call", "function_result", "tool_result", "context", "unknown"]),
    );

    const toolResultEdges = edges.filter((edge) => edge.animated);
    expect(toolResultEdges).toHaveLength(1);
    expect(toolResultEdges[0]?.id).toMatch(/^tool-result-for:/);
  });

  it("keeps the DAG connected by collapsing hidden parent nodes", async () => {
    const filteredGraph: ConversationGraph = {
      selectedSession: "test",
      scope: "lineage",
      nodes: [
        { id: "user", parentIds: [], timestamp: "", agentId: "bob", kind: "user", preview: "", toolName: null, callId: null, documentCount: null },
        { id: "call", parentIds: ["user"], timestamp: "", agentId: "Gamma", kind: "function_call", preview: "", toolName: null, callId: null, documentCount: null },
        { id: "result", parentIds: ["call"], timestamp: "", agentId: "Gamma", kind: "function_result", preview: "", toolName: null, callId: null, documentCount: null },
        { id: "answer", parentIds: ["result"], timestamp: "", agentId: "Gamma", kind: "assistant", preview: "", toolName: null, callId: null, documentCount: null },
      ],
      edges: [
        { id: "parent:user:call", source: "user", target: "call", kind: "parent" },
        { id: "parent:call:result", source: "call", target: "result", kind: "parent" },
        { id: "parent:result:answer", source: "result", target: "answer", kind: "parent" },
        { id: "tool-result-for:result:call", source: "call", target: "result", kind: "tool-result-for" },
      ],
      heads: [{ sessionId: "test", nodeId: "answer" }],
      truncated: false,
    };

    const { nodes, edges } = await createFlowGraph(filteredGraph, new Set(["user", "assistant"]));

    expect(nodes.map((node) => node.id)).toEqual(["user", "answer"]);
    expect(edges).toHaveLength(1);
    expect(edges[0]).toMatchObject({ id: "collapsed:user:answer", source: "user", target: "answer" });
  });

  it("keeps edges whose endpoints are visible", async () => {
    const { nodes, edges } = await createFlowGraph(
      graph,
      new Set(["function_call", "function_result"]),
    );

    expect(nodes).toHaveLength(2);
    expect(edges).toHaveLength(2);
    expect(edges.some((edge) => edge.animated)).toBe(true);
    expect(edges.every((edge) => nodes.some((node) => node.id === edge.source) && nodes.some((node) => node.id === edge.target))).toBe(true);
  });

  it("finds both ancestor and descendant nodes for focus mode", () => {
    const resultEdge = graph.edges.find((edge) => edge.kind === "tool-result-for");
    expect(resultEdge).toBeDefined();
    const focused = relatedNodeIds(graph, resultEdge!.target);

    expect(focused).toContain(resultEdge!.source);
    expect(focused).toContain(resultEdge!.target);
    expect(focused.size).toBe(graph.nodes.length);
  });
});
