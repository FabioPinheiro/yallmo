import cytoscape from "cytoscape";
import { ConversationGraphModel, messageKey, sessionKey } from "./conversation-graph-model.js";
import { GraphInspector, element } from "./conversation-graph-inspector.js";
import { GraphFilter, GraphSearch } from "./conversation-graph-search.js";

// Only patches changed elements. Never rerun layout/fit on ordinary snapshot refreshes.
export function patchGraph(cy, snapshot) {
  const desired = new Map([...snapshot.nodes, ...snapshot.edges]);
  cy.batch(() => {
    cy.elements().forEach(item => { if (!desired.has(item.id())) item.remove(); });
    for (const [id, data] of desired) {
      const current = cy.getElementById(id);
      const position = snapshot.positions.get(id);
      if (current.empty()) cy.add({ group: position ? "nodes" : "edges", data, ...(position ? { position } : {}) });
      else {
        // Cytoscape treats edge endpoints as immutable data; session pointers need move().
        if (data.source && (current.data("source") !== data.source || current.data("target") !== data.target)) {
          current.move({ source: data.source, target: data.target });
        }
        if (JSON.stringify(current.data()) !== JSON.stringify(data)) current.data(data);
        if (position && (current.position("x") !== position.x || current.position("y") !== position.y)) current.position(position);
      }
    }
  });
}

// View-only: hidden inventory remains mounted and keeps its globally allocated coordinates.
export function applyGraphVisibility(cy, visible) {
  cy.batch(() => {
    cy.nodes().forEach(node => node.toggleClass("filtered", !visible.has(node.id())));
    cy.edges().forEach(edge => edge.toggleClass("filtered", !visible.has(edge.source().id()) || !visible.has(edge.target().id())));
  });
}

// Explicit navigation is queued once; snapshot reconciliation must never repeatedly change the viewport.
export class GraphSessionNavigation {
  constructor(available, navigate, selected = () => {}) {
    this.available = available;
    this.navigate = navigate;
    this.selectionChanged = selected;
    this.selected = null;
    this.pending = null;
  }

  select(id) {
    this.pending = null;
    if (this.selected !== id) {
      this.selected = id;
      this.selectionChanged(id ?? undefined);
    }
  }

  request(id) {
    this.select(id);
    this.pending = id;
    this.reconcile();
  }

  reconcile() {
    if (this.pending !== null && this.available(this.pending)) {
      const id = this.pending;
      this.pending = null;
      this.navigate(id);
    } else if (this.pending === null && this.selected !== null && !this.available(this.selected)) this.select(null);
  }
}

function graphStyle(window, host) {
  const css = window.getComputedStyle(host);
  const color = (name, fallback) => css.getPropertyValue(name).trim() || fallback;
  return [
    { selector: "node", style: { label: "data(label)", "font-size": 11, color: color("--text", "#e2e8f0"),
      "text-valign": "bottom", "text-margin-y": 8, "background-color": color("--text-muted", "#a3b1c4"),
      "text-max-width": 160, "text-wrap": "ellipsis", width: 24, height: 24, "border-width": 2, "border-color": color("--border", "#344256") } },
    { selector: 'node[kind = "session"]', style: { shape: "round-rectangle", width: 46, height: 30, "background-color": "#c4a7ff" } },
    { selector: 'node[role = "user"]', style: { "background-color": "#78b3ff" } },
    { selector: 'node[role = "assistant"]', style: { "background-color": "#7ed9ae" } },
    { selector: 'node[role = "tool_call"], node[role = "tool_result"]', style: { shape: "diamond", "background-color": "#f3cb80" } },
    { selector: 'node[kind = "missing"]', style: { shape: "hexagon", "background-opacity": 0, "border-style": "dashed", "border-color": "#ffb4c0" } },
    { selector: "edge", style: { width: 1.5, "curve-style": "bezier", "target-arrow-shape": "triangle",
      "line-color": color("--control-border", "#61738c"), "target-arrow-color": color("--control-border", "#61738c") } },
    { selector: 'edge[kind = "ancestry"]', style: { "curve-style": "segments", "segment-weights": "data(segmentWeights)", "segment-distances": "data(segmentDistances)", "edge-distances": "node-position" } },
    { selector: ".filtered", style: { display: "none" } },
    { selector: 'edge[kind = "head"]', style: { "line-style": "dashed", "line-color": "#c4a7ff", "target-arrow-color": "#c4a7ff" } },
    { selector: "edge[?layoutWarning]", style: { "line-style": "dotted" } },
    { selector: "edge[?anomaly]", style: { "line-color": "#ffb4c0", "target-arrow-color": "#ffb4c0" } },
    { selector: "node.history", style: { "border-color": "#f0a8c7", "border-width": 4 } },
    { selector: "edge.history", style: { "line-color": "#f0a8c7", "target-arrow-color": "#f0a8c7", width: 4 } },
    { selector: ":selected", style: { "border-color": color("--accent", "#78b3ff"), "border-width": 5 } }
  ];
}

export function createConversationGraph(host, inspect, selected = () => {}, liveChanged = () => {}, messageSelected = () => {}) {
  const document = host.ownerDocument, window = document.defaultView;
  const model = new ConversationGraphModel();
  const root = element(document, "div", "conversation-graph");
  const viewport = element(document, "div", "graph-viewport");
  const canvas = element(document, "div", "graph-canvas");
  canvas.setAttribute("aria-hidden", "true");
  const layer = element(document, "div", "graph-card-layer");
  viewport.append(canvas, layer);
  const empty = element(document, "p", "graph-empty muted");
  empty.setAttribute("role", "status");
  empty.hidden = true;
  const search = new GraphSearch(document, query => model.search(query), id => {
    open(id);
    cy.center(cy.getElementById(id));
    inspector.recover();
  }, liveChanged);
  root.append(search.root, empty, viewport);
  host.append(root);
  const cy = cytoscape({ container: canvas, elements: [], layout: { name: "preset" }, style: graphStyle(window, host),
    minZoom: .08, maxZoom: 3, wheelSensitivity: .2, autoungrabify: true });
  let destroyed = false, fitted = false, focusAnchor = null;
  const filter = new GraphFilter(document, () => applyVisibility());
  search.root.insertBefore(filter.root, search.root.lastChild);
  const applyVisibility = () => {
    const visible = model.visible(filter.select.value, focusAnchor);
    applyGraphVisibility(cy, visible);
    if (inspector.card && !visible.has(inspector.card.id)) inspector.close();
  };
  const highlight = (id, focus = true) => {
    const path = model.history(id);
    cy.elements().removeClass("history");
    cy.nodes().forEach(node => { if (path.has(node.id()) || node.id() === sessionKey(id)) node.addClass("history"); });
    cy.edges().forEach(edge => {
      if (path.has(edge.target().id()) && (path.has(edge.source().id()) || edge.source().id() === sessionKey(id))) edge.addClass("history");
    });
    if (focus) cy.fit(cy.elements(".history").not(".filtered"), 60);
  };
  const inspector = new GraphInspector(layer, id => {
    const node = cy.getElementById(id);
    return node.empty() ? { x: 16, y: 16 } : node.renderedPosition();
  }, inspect, id => { sessionNavigation.request(id); }, () => search.input.focus());
  const open = id => {
    const node = model.nodes.get(id);
    if (!node) return;
    focusAnchor = id;
    if (node.kind !== "session") sessionNavigation.select(null);
    applyVisibility();
    messageSelected(node.kind === "message" ? node.rawId : undefined);
    cy.elements().unselect();
    cy.getElementById(id).select();
    if (node.kind === "session") {
      sessionNavigation.select(node.rawId);
      highlight(node.rawId, false);
    }
    inspector.open(node);
  };
  const sessionNavigation = new GraphSessionNavigation(
    id => model.sessions.has(id),
    id => { open(sessionKey(id)); highlight(id); inspector.recover(); },
    selected
  );
  let lastTouch = 0;
  cy.on("tapstart", event => {
    const original = event.originalEvent;
    if (original?.pointerType === "touch" || original?.type?.startsWith("touch")) lastTouch = Date.now();
  });
  cy.on("mouseover mousemove", "node", event => {
    const original = event.originalEvent;
    if (original?.pointerType === "touch" || original?.type?.startsWith("touch") || original?.sourceCapabilities?.firesTouchEvents || Date.now() - lastTouch < 800) return;
    const node = model.nodes.get(event.target.id());
    if (node && !event.target.hasClass("filtered")) inspector.preview(node);
  });
  cy.on("mouseout", "node", event => inspector.leaveNode(event.target.id()));
  const leaveCanvas = () => inspector.leaveNode(inspector.hoveredNode);
  canvas.addEventListener("pointerleave", leaveCanvas);
  cy.on("tap", "node", event => open(event.target.id()));
  cy.on("tap", event => { if (event.target === cy) { inspector.close(); messageSelected(undefined); cy.elements().unselect(); } });
  cy.on("pan zoom resize", () => { inspector.dismissPreview(); inspector.updatePosition(); });
  const resize = new window.ResizeObserver(() => { cy.resize(); inspector.recover(); });
  resize.observe(viewport);
  const theme = new window.MutationObserver(() => cy.style(graphStyle(window, host)));
  theme.observe(document.documentElement, { attributes: true, attributeFilter: ["class", "style", "data-theme"] });
  const update = (messages, sessions, replace = false) => {
    if (destroyed) return;
    const snapshot = replace ? model.replaceInventory(messages, sessions) : model.reconcile(messages, sessions);
    patchGraph(cy, snapshot);
    applyVisibility();
    if (sessionNavigation.selected !== null) highlight(sessionNavigation.selected, false);
    else cy.elements().removeClass("history");
    search.update();
    if (inspector.card) {
      const node = model.nodes.get(inspector.card.id);
      if (node) inspector.update(node);
      else inspector.close();
    }
    empty.hidden = snapshot.nodes.size > 0;
    empty.textContent = snapshot.nodes.size ? "" : "No stored messages or sessions.";
    if (!fitted && snapshot.nodes.size) { cy.resize(); cy.fit(undefined, 60); fitted = true; }
    inspector.updatePosition();
    sessionNavigation.reconcile();
  };
  return {
    update: (messages, sessions) => update(messages, sessions, true),
    appendMessages(messages, head) {
      update(messages, [...model.sessions.values()]);
      const id = messageKey(head);
      if (!destroyed && head && model.nodes.get(id)?.kind === "message") {
        focusAnchor = id;
        sessionNavigation.select(null);
        applyVisibility();
        cy.elements().removeClass("history").unselect();
        cy.getElementById(id).select();
      }
    },
    navigateSession(id) {
      if (!destroyed) {
        cy.elements().removeClass("history").unselect();
        sessionNavigation.request(id);
      }
    },
    destroy() {
      if (destroyed) return;
      destroyed = true;
      sessionNavigation.select(null);
      resize.disconnect();
      theme.disconnect();
      canvas.removeEventListener("pointerleave", leaveCanvas);
      inspector.destroy();
      cy.destroy();
      root.remove();
    }
  };
}
