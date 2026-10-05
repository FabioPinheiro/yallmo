import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  type ReactFlowInstance,
  type NodeTypes,
} from "@xyflow/react";
import type { ConversationGraph, GraphNodeKind, GraphScope, MessageDetail, SessionSummary } from "./api";
import { createFlowGraph, relatedNodeIds, type VisualEdge, type VisualNode } from "./graph";
import {
  allSessionsId,
  storageAllSessionsGraph,
  storageGraph,
  storageMessageDetail,
  storageSessions,
} from "./storage-snapshot";
import { ConversationNode } from "./nodes";

const nodeTypes = { conversation: ConversationNode } as NodeTypes;
const kinds: GraphNodeKind[] = [
  "user",
  "assistant",
  "reasoning",
  "rag_result",
  "function_call",
  "function_result",
  "tool_result",
  "context",
  "unknown",
];
const functionKinds = ["function_call", "function_result"] as const;

const kindLabels: Record<GraphNodeKind, string> = {
  user: "User",
  assistant: "Assistant",
  reasoning: "Reasoning",
  rag_result: "RAG",
  function_call: "Function call",
  function_result: "Function result",
  tool_result: "Tool result",
  context: "Context",
  unknown: "Unknown",
};

const initialSessionId = storageSessions[0]?.id ?? "";

function graphForSelection(sessionId: string, scope: GraphScope): ConversationGraph {
  return sessionId === allSessionsId ? storageAllSessionsGraph() : storageGraph(sessionId, scope);
}

export function App() {
  const [sessions] = useState<SessionSummary[]>(storageSessions);
  const [sessionId, setSessionId] = useState(initialSessionId);
  const [scope, setScope] = useState<GraphScope>("family");
  const [graph, setGraph] = useState<ConversationGraph>(() => graphForSelection(initialSessionId, "family"));
  const [visibleKinds, setVisibleKinds] = useState<Set<GraphNodeKind>>(
    () => new Set(kinds.filter((kind) => kind !== "reasoning" && kind !== "context")),
  );
  const [selectedId, setSelectedId] = useState<string>();
  const [detail, setDetail] = useState<MessageDetail>();
  const [nodes, setNodes] = useState<VisualNode[]>([]);
  const [edges, setEdges] = useState<VisualEdge[]>([]);
  const [focus, setFocus] = useState(false);
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [flow, setFlow] = useState<ReactFlowInstance>();

  const visibleIds = useMemo(
    () => (focus && selectedId ? relatedNodeIds(graph, selectedId) : undefined),
    [focus, graph, selectedId],
  );

  const selectNode = useCallback(
    async (id: string) => {
      setSelectedId(id);
      setNodes((current) => current.map((node) => ({ ...node, data: { ...node.data, selected: node.id === id } })));
      setDetail(undefined);
      try {
        const nextDetail = storageMessageDetail(id);
        if (!nextDetail) throw new Error("No detail is available for this message.");
        setDetail(nextDetail);
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : "Unable to load message detail.");
      }
    },
    [],
  );

  const selectedForLayout = focus ? selectedId : undefined;

  useEffect(() => {
    let cancelled = false;
    createFlowGraph(graph, visibleKinds, visibleIds, selectedForLayout, selectNode)
      .then(({ nodes: nextNodes, edges: nextEdges }) => {
        if (!cancelled) {
          setNodes(nextNodes);
          setEdges(nextEdges);
          requestAnimationFrame(() => flow?.fitView({ padding: 0.18, duration: 250 }));
        }
      })
      .catch(() => !cancelled && setError("Unable to lay out the conversation graph."));
    return () => {
      cancelled = true;
    };
  }, [flow, graph, selectedForLayout, selectNode, visibleIds, visibleKinds]);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      setGraph(graphForSelection(sessionId, scope));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Unable to load the graph.");
    } finally {
      setLoading(false);
    }
  }, [scope, sessionId]);

  const updateSession = (nextSessionId: string) => {
    setSessionId(nextSessionId);
    setSelectedId(undefined);
    setDetail(undefined);
    setGraph(graphForSelection(nextSessionId, scope));
  };

  const updateScope = (nextScope: GraphScope) => {
    setScope(nextScope);
    setSelectedId(undefined);
    setDetail(undefined);
    setGraph(graphForSelection(sessionId, nextScope));
  };

  const toggleKind = (kind: GraphNodeKind) => {
    setVisibleKinds((current) => {
      const next = new Set(current);
      if (functionKinds.includes(kind as (typeof functionKinds)[number])) {
        const functionsAreVisible = functionKinds.every((functionKind) => next.has(functionKind));
        for (const functionKind of functionKinds) {
          if (functionsAreVisible) next.delete(functionKind);
          else next.add(functionKind);
        }
      } else if (next.has(kind)) {
        next.delete(kind);
      } else {
        next.add(kind);
      }
      return next;
    });
  };

  return (
    <main className="app-shell">
      <header className="app-header">
        <div>
          <p className="eyebrow">Read-only local explorer</p>
          <h1>Yallmo conversation graph</h1>
        </div>
        <div className="header-actions">
          <span className="source-pill source-snapshot">zzz snapshot</span>
          <button type="button" className="primary-button" onClick={refresh} disabled={loading}>{loading ? "Loading…" : "Reload snapshot"}</button>
        </div>
      </header>

      {error && <div className="error-banner" role="alert"><span>{error}</span><button type="button" onClick={() => setError(undefined)}>Dismiss</button></div>}
      {graph.truncated && <div className="notice-banner">This graph was truncated by the server limit. Refine the session or focus a smaller path.</div>}

      <section className="toolbar" aria-label="Graph controls">
        <label>Session
          <select value={sessionId} onChange={(event) => updateSession(event.target.value)}>
            <option value={allSessionsId}>All sessions ({sessions.length})</option>
            {sessions.map((session) => <option key={session.id} value={session.id}>{session.id} ({session.messageCount})</option>)}
          </select>
        </label>
        <label>Scope
          <select value={scope} onChange={(event) => updateScope(event.target.value as GraphScope)} disabled={sessionId === allSessionsId}>
            <option value="lineage">Selected lineage</option>
            <option value="family">Fork family</option>
          </select>
        </label>
        <fieldset className="filter-set">
          <legend>Types</legend>
          {kinds.map((kind) => (
            <label className={`filter-chip kind-${kind}`} key={kind}>
              <input type="checkbox" checked={visibleKinds.has(kind)} onChange={() => toggleKind(kind)} />
              {kindLabels[kind]}
            </label>
          ))}
        </fieldset>
        <div className="focus-actions">
          <button type="button" className="secondary-button" disabled={!selectedId} onClick={() => setFocus((value) => !value)}>
            {focus ? "Show full graph" : "Show related"}
          </button>
          <button type="button" className="text-button" onClick={() => { setFocus(false); setSelectedId(undefined); setDetail(undefined); }}>Reset</button>
        </div>
      </section>

      <section className="workspace">
        <div className="graph-canvas" aria-label="Conversation DAG canvas">
          {nodes.length > 0 ? (
            <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} onInit={setFlow} nodesDraggable={false} nodesConnectable={false} fitView>
              <Background gap={20} size={1} color="#cbd5e1" />
              <Controls showInteractive={false} />
              <MiniMap pannable zoomable nodeColor={(node) => `var(--${(node.data as VisualNode["data"]).graphNode.kind}-color)`} />
            </ReactFlow>
          ) : <p className="empty-graph">No messages match the selected filters.</p>}
        </div>

        <aside className="detail-panel" aria-live="polite">
          <div className="panel-heading"><h2>Message detail</h2>{selectedId && <button type="button" className="text-button" onClick={() => { setSelectedId(undefined); setDetail(undefined); }}>Close</button>}</div>
          {!selectedId && <p>Select a node to inspect its safe, complete detail.</p>}
          {selectedId && !detail && <p>Loading detail…</p>}
          {detail && <>
            <div className={`detail-kind kind-${detail.kind}`}>{kindLabels[detail.kind]}</div>
            <dl className="detail-meta">
              <dt>Message</dt><dd>{detail.id}</dd>
              <dt>Agent</dt><dd>{detail.agentId}</dd>
              <dt>Time</dt><dd>{new Date(detail.timestamp).toLocaleString()}</dd>
              {detail.continuationExists !== undefined && <><dt>Continuation</dt><dd>{detail.continuationExists ? "Present (redacted)" : "None"}</dd></>}
            </dl>
            <h3>Content</h3>
            <pre>{detail.content}</pre>
            <h3>Metadata</h3>
            <dl className="detail-meta">{Object.entries(detail.metadata).map(([key, value]) => <><dt key={`${key}-label`}>{key}</dt><dd key={key}>{String(value)}</dd></>)}</dl>
          </>}
        </aside>
      </section>
    </main>
  );
}
