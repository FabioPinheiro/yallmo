import snapshotJson from "./generated/zzz-conversation.json";
import type { ConversationGraph, GraphScope, MessageDetail, SessionSummary } from "./api";

interface StorageSnapshot {
  sessions: SessionSummary[];
  graphs: Record<string, Record<GraphScope, ConversationGraph>>;
  details: Record<string, MessageDetail>;
}

const snapshot = snapshotJson as StorageSnapshot;

export const storageSessions = snapshot.sessions;
export const allSessionsId = "__all_sessions__";

export function storageGraph(sessionId: string, scope: GraphScope): ConversationGraph {
  const graph = snapshot.graphs[sessionId]?.[scope];
  if (!graph) throw new Error(`Session '${sessionId}' is not present in the zzz snapshot.`);
  return graph;
}

export function storageAllSessionsGraph(): ConversationGraph {
  const lineages = Object.values(snapshot.graphs).map((scopes) => scopes.lineage);
  const nodes = new Map(lineages.flatMap((graph) => graph.nodes.map((node) => [node.id, node])));
  const edges = new Map(lineages.flatMap((graph) => graph.edges.map((edge) => [edge.id, edge])));

  return {
    selectedSession: allSessionsId,
    scope: "lineage",
    nodes: [...nodes.values()],
    edges: [...edges.values()],
    heads: lineages.flatMap((graph) => graph.heads),
    truncated: lineages.some((graph) => graph.truncated),
  };
}

export function storageMessageDetail(messageId: string): MessageDetail | undefined {
  return snapshot.details[messageId];
}
