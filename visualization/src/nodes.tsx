import { Handle, Position, type NodeProps } from "@xyflow/react";
import type { CSSProperties } from "react";
import type { GraphNodeKind } from "./api";
import type { VisualNode } from "./graph";

const labels: Record<GraphNodeKind, string> = {
  user: "User",
  assistant: "Assistant",
  reasoning: "Reasoning",
  rag_result: "RAG result",
  function_call: "Function call",
  function_result: "Function result",
  tool_result: "Tool result",
  context: "Context",
  unknown: "Unknown",
};

const icons: Record<GraphNodeKind, string> = {
  user: "●",
  assistant: "✦",
  reasoning: "◌",
  rag_result: "⌕",
  function_call: "↗",
  function_result: "✓",
  tool_result: "✓",
  context: "□",
  unknown: "?",
};

export function ConversationNode({ data }: NodeProps<VisualNode>) {
  const { graphNode, isHead, selected, onSelect } = data;
  const isError = /\b(error|failed|denied)\b/i.test(graphNode.preview);

  return (
    <button
      type="button"
      className={`conversation-node kind-${graphNode.kind} ${selected ? "is-selected" : ""} ${isError ? "is-error" : ""}`}
      style={{ "--agent-color": data.agentColor } as CSSProperties}
      onClick={() => onSelect(graphNode.id)}
      aria-label={`Inspect ${labels[graphNode.kind]} message`}
    >
      <Handle type="target" position={Position.Top} />
      <div className="node-heading">
        <span className="node-kind"><span aria-hidden="true">{icons[graphNode.kind]}</span> {labels[graphNode.kind]}</span>
        {isHead && <span className="head-badge">head</span>}
      </div>
      <p>{graphNode.preview}</p>
      <div className="node-meta">
        <span className="agent-id">{graphNode.toolName ?? graphNode.agentId}</span>
        <time dateTime={graphNode.timestamp}>{new Date(graphNode.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</time>
      </div>
      <Handle type="source" position={Position.Bottom} />
    </button>
  );
}
