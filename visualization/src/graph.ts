import ELK from "elkjs/lib/elk.bundled.js";
import type { Edge, Node } from "@xyflow/react";
import type { ConversationGraph, GraphEdge, GraphNode, GraphNodeKind } from "./api";

export interface VisualNodeData extends Record<string, unknown> {
  graphNode: GraphNode;
  isHead: boolean;
  selected: boolean;
  onSelect: (id: string) => void;
  agentColor: string;
}

export type VisualNode = Node<VisualNodeData, "conversation">;
export type VisualEdge = Edge;

const elk = new ELK();
const nodeWidth = 244;
const nodeHeight = 94;

export async function createFlowGraph(
  graph: ConversationGraph,
  visibleKinds: ReadonlySet<GraphNodeKind>,
  visibleIds?: ReadonlySet<string>,
  selectedId?: string,
  onSelect: (id: string) => void = () => undefined,
): Promise<{ nodes: VisualNode[]; edges: VisualEdge[] }> {
  const graphNodes = graph.nodes.filter(
    (node) => visibleKinds.has(node.kind) && (!visibleIds || visibleIds.has(node.id)),
  );
  const agentColors = colorsByAgent(graph.nodes);
  const renderedIds = new Set(graphNodes.map((node) => node.id));
  const graphEdges = visibleEdges(graph.edges, renderedIds);
  const headIds = new Set(graph.heads.map((head) => head.nodeId));
  const layout = await elk.layout({
    id: "conversation",
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": "DOWN",
      "elk.edgeRouting": "ORTHOGONAL",
      "elk.layered.spacing.nodeNodeBetweenLayers": "80",
      "elk.spacing.nodeNode": "36",
      "elk.padding": "[top=24,left=24,bottom=24,right=24]",
    },
    children: graphNodes.map((node) => ({
      id: node.id,
      width: nodeWidth,
      height: nodeHeight,
    })),
    edges: graphEdges.map((edge) => ({
      id: edge.id,
      sources: [edge.source],
      targets: [edge.target],
    })),
  });
  const positions = new Map(layout.children?.map((node) => [node.id, node]) ?? []);

  return {
    nodes: graphNodes.map((node) => {
      const layoutNode = positions.get(node.id);
      return {
        id: node.id,
        type: "conversation",
        position: { x: layoutNode?.x ?? 0, y: layoutNode?.y ?? 0 },
        data: {
          graphNode: node,
          isHead: headIds.has(node.id),
          selected: selectedId === node.id,
          onSelect,
          agentColor: agentColors.get(node.agentId) ?? "#475569",
        },
      };
    }),
    edges: graphEdges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      type: "smoothstep",
      animated: edge.kind === "tool-result-for",
      label: edge.kind === "tool-result-for" ? "result" : undefined,
      style:
        edge.kind === "tool-result-for"
          ? { stroke: "#f59e0b", strokeDasharray: "5 4" }
          : { stroke: "#64748b", strokeWidth: 1.5 },
      labelStyle: { fill: "#b45309", fontSize: 10 },
    })),
  };
}

export function colorsByAgent(nodes: readonly GraphNode[]): Map<string, string> {
  const agentIds = [...new Set(nodes.map((node) => node.agentId))].sort();
  return new Map(
    agentIds.map((agentId, index) => [
      agentId,
      `hsl(${Math.round((index * 360) / Math.max(agentIds.length, 1))} 65% 38%)`,
    ]),
  );
}

function visibleEdges(edges: readonly GraphEdge[], visibleIds: ReadonlySet<string>): GraphEdge[] {
  const parentChildren = new Map<string, GraphEdge[]>();
  const directVisibleParents = new Map<string, GraphEdge>();
  const semanticEdges: GraphEdge[] = [];

  for (const edge of edges) {
    if (edge.kind === "parent") {
      const children = parentChildren.get(edge.source) ?? [];
      children.push(edge);
      parentChildren.set(edge.source, children);
      if (visibleIds.has(edge.source) && visibleIds.has(edge.target)) {
        directVisibleParents.set(`${edge.source}:${edge.target}`, edge);
      }
    } else if (visibleIds.has(edge.source) && visibleIds.has(edge.target)) {
      semanticEdges.push(edge);
    }
  }

  const collapsedParents = new Map(directVisibleParents);
  for (const source of visibleIds) {
    const pending = (parentChildren.get(source) ?? []).map((edge) => ({ edge, throughHidden: false }));
    const visited = new Set<string>([source]);

    while (pending.length > 0) {
      const { edge, throughHidden } = pending.pop()!;
      if (visited.has(edge.target)) continue;
      visited.add(edge.target);

      if (visibleIds.has(edge.target)) {
        const collapsed = throughHidden
          ? { ...edge, id: `collapsed:${source}:${edge.target}`, source, target: edge.target }
          : edge;
        const endpointKey = `${source}:${edge.target}`;
        if (!collapsedParents.has(endpointKey)) collapsedParents.set(endpointKey, collapsed);
      } else {
        for (const child of parentChildren.get(edge.target) ?? []) {
          pending.push({ edge: child, throughHidden: true });
        }
      }
    }
  }

  return [...collapsedParents.values(), ...semanticEdges];
}

export function relatedNodeIds(graph: ConversationGraph, selectedId: string): Set<string> {
  const backwards = new Map<string, string[]>();
  const forwards = new Map<string, string[]>();

  for (const edge of graph.edges) {
    const from = backwards.get(edge.target) ?? [];
    from.push(edge.source);
    backwards.set(edge.target, from);

    const to = forwards.get(edge.source) ?? [];
    to.push(edge.target);
    forwards.set(edge.source, to);
  }

  const related = new Set([selectedId]);
  const visit = (map: Map<string, string[]>) => {
    const pending = [selectedId];
    while (pending.length > 0) {
      const id = pending.pop()!;
      for (const neighbour of map.get(id) ?? []) {
        if (!related.has(neighbour)) {
          related.add(neighbour);
          pending.push(neighbour);
        }
      }
    }
  };

  visit(backwards);
  visit(forwards);
  return related;
}
