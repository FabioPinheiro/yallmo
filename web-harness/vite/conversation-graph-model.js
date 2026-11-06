// Pure, ID-keyed state. Message artifacts accumulate; session pointers are replaceable snapshots.
export const messageKey = id => `message:${id}`;
export const sessionKey = id => `session:${id}`;
const spacing = 110;
const laneWidth = 210;
const turnClearance = 55;

// The rendered ancestry elbow normally leaves below the parent label, then descends in the child lane.
export function ancestrySegments(source, target, departure = target.y > source.y ? turnClearance : -turnClearance) {
  const turn = source.y + departure;
  return [[source, { x: source.x, y: turn }],
    [{ x: source.x, y: turn }, { x: target.x, y: turn }],
    [{ x: target.x, y: turn }, target]];
}

export function ancestryBends(source, target, departure) {
  const bends = ancestrySegments(source, target, departure).slice(0, 2).map(segment => segment[1]);
  const dx = target.x - source.x, dy = target.y - source.y;
  const length = Math.hypot(dx, dy) || 1;
  return {
    segmentWeights: bends.map(p => ((p.x - source.x) * dx + (p.y - source.y) * dy) / (length * length)).join(" "),
    segmentDistances: bends.map(p => (dx * (p.y - source.y) - dy * (p.x - source.x)) / length).join(" ")
  };
}

const overlaps = (a, b, c, d) => Math.max(Math.min(a, b), Math.min(c, d)) < Math.min(Math.max(a, b), Math.max(c, d));
const hitsNode = ([a, b], point) => a.x === b.x
  ? Math.abs(a.x - point.x) < 90 && overlaps(a.y, b.y, point.y - 42, point.y + 42)
  : Math.abs(a.y - point.y) < 42 && overlaps(a.x, b.x, point.x - 90, point.x + 90);
const timeValue = timestamp => {
  const value = Date.parse(timestamp);
  return Number.isFinite(value) ? value : null;
};

export class ConversationGraphModel {
  constructor() {
    this.messages = new Map();
    this.sessions = new Map();
    this.positions = new Map();
    this.nodes = new Map();
    this.edges = new Map();
    this.origin = null;
    this.routes = new Map();
  }

  replaceInventory(messages, sessions) {
    const retained = new Set(messages.map(message => message.id));
    for (const id of this.messages.keys()) if (!retained.has(id)) this.messages.delete(id);
    const snapshot = this.reconcile(messages, sessions);
    for (const id of this.positions.keys()) if (!snapshot.nodes.has(id)) this.positions.delete(id);
    for (const id of this.routes.keys()) if (!snapshot.edges.has(id)) this.routes.delete(id);
    return snapshot;
  }

  reconcile(messages, sessions) {
    for (const message of messages) this.messages.set(message.id, message);
    this.sessions = new Map(sessions.map(session => {
      const previous = this.sessions.get(session.id);
      // An unreadable session is not an empty session. Keep the last known pointer, marked stale.
      return [session.id, session.error && previous ? { ...previous, error: session.error } : session];
    }));
    const nodes = new Map();
    const edges = new Map();
    const missing = id => {
      const key = messageKey(id);
      if (!nodes.has(key)) nodes.set(key, { id: key, rawId: id, kind: "missing", label: `Missing · ${id.slice(0, 16)}` });
    };
    for (const message of this.messages.values()) {
      const id = messageKey(message.id);
      nodes.set(id, { ...message, id, rawId: message.id, kind: "message", label: `${message.role} · ${message.id.slice(-10)}` });
    }
    for (const message of this.messages.values()) {
      if (message.parentId) {
        if (!this.messages.has(message.parentId)) missing(message.parentId);
        const id = `parent:${message.id}`;
        edges.set(id, { id, source: messageKey(message.parentId), target: messageKey(message.id), kind: "ancestry" });
      }
    }
    for (const session of this.sessions.values()) {
      const id = sessionKey(session.id);
      nodes.set(id, { ...session, id, rawId: session.id, kind: "session", label: session.id + (session.error ? " (unreadable)" : "") });
      if (session.head) {
        if (!this.messages.has(session.head)) missing(session.head);
        const edgeId = `head:${session.id}`;
        edges.set(edgeId, { id: edgeId, source: id, target: messageKey(session.head), kind: "head" });
      }
    }
    this.layout(nodes, edges);
    for (const edge of edges.values()) {
      const parent = nodes.get(edge.source), child = nodes.get(edge.target);
      const pt = timeValue(parent.timestamp), ct = timeValue(child.timestamp);
      if (edge.kind === "ancestry") {
        const route = this.routes.get(edge.id);
        Object.assign(edge, ancestryBends(this.positions.get(edge.source), this.positions.get(edge.target), route.departure));
        edge.layoutWarning = route.fallback;
      }
      edge.anomaly = edge.kind === "ancestry" && ((pt !== null && ct !== null && ct < pt) ||
        this.positions.get(edge.source).y >= this.positions.get(edge.target).y);
    }
    this.nodes = nodes;
    this.edges = edges;
    return { nodes, edges, positions: this.positions };
  }

  layout(nodes, edges) {
    const times = [...this.messages.values()].map(m => timeValue(m.timestamp)).filter(t => t !== null);
    if (this.origin === null && times.length) this.origin = times.reduce((minimum, time) => Math.min(minimum, time), Infinity);
    // Signed logarithmic UTC distance compresses idle periods. It is not a proportional time axis.
    const timeY = timestamp => {
      const time = timeValue(timestamp);
      const delta = time === null || this.origin === null ? 0 : (time - this.origin) / 1000;
      return Math.sign(delta) * Math.log1p(Math.abs(delta)) * 28;
    };
    const children = new Map();
    for (const node of nodes.values()) {
      if (node.kind === "message" && node.parentId) {
        const parent = messageKey(node.parentId);
        if (!children.has(parent)) children.set(parent, []);
        children.get(parent).push(node.id);
      }
    }
    const pending = [...nodes.values()].filter(n => n.kind !== "session" && !this.positions.has(n.id))
      .sort((a, b) => (timeValue(a.timestamp) ?? 0) - (timeValue(b.timestamp) ?? 0) || a.id.localeCompare(b.id));
    const placed = new Map([...nodes.values()].filter(n => n.kind !== "session" && this.positions.has(n.id))
      .map(n => [n.id, this.positions.get(n.id)]));
    const reserved = [];
    const clearRoute = (edge, segments, occupied, routes) =>
      ![...occupied].some(([id, p]) => id !== edge.source && id !== edge.target && segments.some(segment => hitsNode(segment, p))) &&
      !routes.some(old => segments.some(([a, b], i) => a.x === b.x && a.y !== b.y &&
        old.segments.some(([c, d], j) => !(edge.source === old.source && i === 0 && j === 0) && c.x === d.x && c.y !== d.y && a.x === c.x && overlaps(a.y, b.y, c.y, d.y))));
    const routeEdge = (edge, occupied, routes, fallback = false) => {
      const source = occupied.get(edge.source), target = occupied.get(edge.target);
      const normal = target.y > source.y ? turnClearance : -turnClearance;
      for (const departure of [normal, 0, -normal]) {
        const segments = ancestrySegments(source, target, departure);
        if (clearRoute(edge, segments, occupied, routes)) return { ...edge, departure, segments, fallback: false };
      }
      return fallback ? { ...edge, departure: normal, segments: ancestrySegments(source, target, normal), fallback: true } : null;
    };
    for (const edge of edges.values()) if (edge.kind === "ancestry" && placed.has(edge.source) && placed.has(edge.target)) {
      const saved = this.routes.get(edge.id);
      const route = saved ? { ...edge, ...saved, segments: ancestrySegments(placed.get(edge.source), placed.get(edge.target), saved.departure) }
        : routeEdge(edge, placed, reserved, true);
      reserved.push(route);
      this.routes.set(edge.id, { departure: route.departure, fallback: route.fallback });
    }
    // Iterative traversal: no stack/depth limit. A late missing ancestor sits above its fixed children.
    while (pending.length) {
      let index = pending.findIndex(n => !n.parentId || placed.has(messageKey(n.parentId)));
      if (index < 0) index = 0; // Corrupt cycles remain visible, with anomalous edges.
      const [node] = pending.splice(index, 1);
      const parentId = node.parentId ? messageKey(node.parentId) : null;
      const parent = placed.get(parentId);
      const fixedChildren = (children.get(node.id) ?? []).filter(id => placed.has(id)).sort();
      const ceiling = fixedChildren.length ? Math.min(...fixedChildren.map(id => placed.get(id).y)) - spacing : Infinity;
      const y = Math.min(ceiling, Math.max(timeY(node.timestamp), parent ? parent.y + spacing : -Infinity));
      const preferred = parent?.x ?? placed.get(fixedChildren[0])?.x ?? 0;
      const connected = [...(parent ? [edges.get(`parent:${node.rawId}`)] : []),
        ...fixedChildren.map(id => edges.get(`parent:${nodes.get(id).rawId}`))];
      let position, chosenRoutes;
      const outer = Math.max(0, ...[...placed.values()].map(p => Math.abs(p.x))) + laneWidth;
      const limit = Math.ceil((outer + Math.abs(preferred)) / laneWidth);
      for (let distance = 0; !position && distance <= limit; distance++) {
        for (const direction of distance ? [1, -1] : [0]) {
          const candidate = { x: preferred + distance * direction * laneWidth, y };
          if ([...placed.values()].some(p => Math.abs(p.x - candidate.x) < 150 && Math.abs(p.y - y) < 80)) continue;
          // A column is occupied only over an edge's actual vertical lifetime, not forever.
          if (reserved.some(edge => edge.source !== node.id && edge.target !== node.id && edge.segments.some(segment => hitsNode(segment, candidate)))) continue;
          const occupied = new Map(placed).set(node.id, candidate), routes = [];
          for (const edge of connected) {
            const route = routeEdge(edge, occupied, [...reserved, ...routes]);
            if (!route) break;
            routes.push(route);
          }
          if (routes.length !== connected.length) continue;
          position = candidate;
          chosenRoutes = routes;
          break;
        }
      }
      // Finite escape for dense/fixed constraints: nodes never collide; routing may have a crossing.
      if (!position) {
        position = { x: outer, y };
        const occupied = new Map(placed).set(node.id, position);
        chosenRoutes = [];
        for (const edge of connected) chosenRoutes.push(routeEdge(edge, occupied, [...reserved, ...chosenRoutes], true));
      }
      this.positions.set(node.id, position);
      placed.set(node.id, position);
      for (const route of chosenRoutes) {
        reserved.push(route);
        this.routes.set(route.id, { departure: route.departure, fallback: route.fallback });
      }
    }
    for (const edge of edges.values()) if (edge.kind === "ancestry" && !this.routes.has(edge.id)) {
      const route = routeEdge(edge, placed, reserved, true);
      reserved.push(route);
      this.routes.set(edge.id, { departure: route.departure, fallback: route.fallback });
    }
    // Session pointers sit below their heads in compact, collision-free slots; messages never move for them.
    const occupied = [...nodes.values()].filter(node => node.kind !== "session").map(node => this.positions.get(node.id));
    const sessions = [...nodes.values()].filter(node => node.kind === "session").sort((a, b) => a.id.localeCompare(b.id));
    for (const node of sessions) {
      const head = node.head ? this.positions.get(messageKey(node.head)) : null;
      let position;
      for (let row = 0; !position; row++) {
        for (const column of [0, 1, -1, 2, -2]) {
          const candidate = { x: (head?.x ?? 0) + column * 160, y: (head ? head.y + spacing : 0) + row * spacing };
          if (!occupied.some(p => Math.abs(p.x - candidate.x) < 150 && Math.abs(p.y - candidate.y) < 80) &&
              !reserved.some(edge => edge.segments.some(segment => hitsNode(segment, candidate)))) {
            position = candidate;
            break;
          }
        }
      }
      this.positions.set(node.id, position);
      occupied.push(position);
    }
  }

  // Current = ancestry plus all descendants. Siblings also includes the closest split's alternative subtrees.
  visible(mode, anchor) {
    if (mode === "all" || !anchor || !this.nodes.has(anchor)) return new Set(this.nodes.keys());
    const selected = this.nodes.get(anchor);
    const head = selected.kind === "session" ? (selected.head ? messageKey(selected.head) : null) : anchor;
    const path = new Set();
    let id = head;
    while (id && this.nodes.has(id) && !path.has(id)) {
      path.add(id);
      const parent = this.nodes.get(id)?.parentId;
      id = parent ? messageKey(parent) : null;
    }
    const visible = new Set(path);
    const children = new Map();
    for (const node of this.nodes.values()) if (node.kind === "message" && node.parentId) {
      const parent = messageKey(node.parentId);
      if (!children.has(parent)) children.set(parent, []);
      children.get(parent).push(node.id);
    }
    const fork = mode === "siblings" ? [...path].find(key => (children.get(key)?.length ?? 0) > 1) : null;
    const start = fork ?? head;
    const pending = start ? [start] : [];
    const visited = new Set();
    while (pending.length) {
      const key = pending.pop();
      if (visited.has(key)) continue;
      visited.add(key);
      visible.add(key);
      pending.push(...children.get(key) ?? []);
    }
    for (const session of this.sessions.values()) {
      if ((session.head && visible.has(messageKey(session.head))) || sessionKey(session.id) === anchor) visible.add(sessionKey(session.id));
    }
    return visible;
  }

  history(sessionId) {
    const path = new Set();
    let id = this.sessions.get(sessionId)?.head;
    while (id && !path.has(messageKey(id))) {
      path.add(messageKey(id));
      id = this.messages.get(id)?.parentId;
    }
    return path;
  }

  search(query) {
    const text = query.trim().toLocaleLowerCase();
    return [...this.nodes.values()].filter(node => !text ||
      [node.rawId, node.content, node.author, node.role, node.label].some(value => value?.toLocaleLowerCase().includes(text)));
  }
}

