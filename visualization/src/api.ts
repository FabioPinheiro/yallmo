export type GraphScope = "lineage" | "family";

export type GraphNodeKind =
  | "user"
  | "assistant"
  | "reasoning"
  | "rag_result"
  | "function_call"
  | "function_result"
  | "tool_result"
  | "context"
  | "unknown";

export type GraphEdgeKind = "parent" | "tool-result-for";

export interface GraphNode {
  id: string;
  parentIds: string[];
  timestamp: string;
  agentId: string;
  kind: GraphNodeKind;
  preview: string;
  toolName: string | null;
  callId: string | null;
  documentCount: number | null;
}

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  kind: GraphEdgeKind;
}

export interface SessionHead {
  sessionId: string;
  nodeId: string;
}

export interface ConversationGraph {
  selectedSession: string;
  scope: GraphScope;
  nodes: GraphNode[];
  edges: GraphEdge[];
  heads: SessionHead[];
  truncated: boolean;
}

export interface MessageDetail {
  id: string;
  kind: GraphNodeKind;
  timestamp: string;
  agentId: string;
  content: string;
  metadata: Record<string, string | number | boolean | null>;
  continuationExists?: boolean;
}

export interface SessionSummary {
  id: string;
  messageCount: number;
  headId: string | null;
  agentId: string | null;
}

interface ApiErrorBody {
  error?: { code?: string; message?: string };
}

async function json<T>(input: RequestInfo | URL): Promise<T> {
  const response = await fetch(input, { headers: { Accept: "application/json" } });

  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as ApiErrorBody;
    throw new Error(body.error?.message ?? `Request failed with ${response.status}`);
  }

  return response.json() as Promise<T>;
}

export async function loadSessions(): Promise<SessionSummary[]> {
  const response = await json<{ sessions: SessionSummary[] }>("/api/v1/sessions");
  return response.sessions;
}

export function loadGraph(sessionId: string, scope: GraphScope): Promise<ConversationGraph> {
  const params = new URLSearchParams({ scope });
  return json<ConversationGraph>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/graph?${params}`);
}

export function loadMessageDetail(sessionId: string, messageId: string): Promise<MessageDetail> {
  return json<MessageDetail>(
    `/api/v1/sessions/${encodeURIComponent(sessionId)}/messages/${encodeURIComponent(messageId)}`,
  );
}
