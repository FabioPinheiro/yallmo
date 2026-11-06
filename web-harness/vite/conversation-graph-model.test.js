import test from "node:test";
import assert from "node:assert/strict";
import { ConversationGraphModel, ancestrySegments, messageKey as m, sessionKey as s } from "./conversation-graph-model.js";

const message = (id, parentId = null, seconds = 0, content = id) => ({ id, parentId,
  timestamp: new Date(Date.UTC(2026, 0, 1) + seconds * 1000).toISOString(), role: "user", author: "human", content });
const point = (model, id) => model.positions.get(m(id));

test("inventory keeps >32 depth, disconnected roots, originals and off-session branches", () => {
  const model = new ConversationGraphModel();
  const chain = Array.from({ length: 150 }, (_, i) => message(`n${i}`, i ? `n${i - 1}` : null, i));
  model.reconcile([...chain, message("original", "n0"), message("disconnected")], [{ id: "main", head: "n149" }]);
  assert.equal(model.messages.size, 152);
  assert.equal(model.nodes.size, 153);
  assert.equal(model.history("main").size, 150);
  assert(!model.history("main").has(m("original")));
  for (let i = 1; i < 150; i++) assert(point(model, `n${i}`).y > point(model, `n${i - 1}`).y);
});

test("equal/copy timestamps separate sibling lanes; ancestry overrides clocks and flags anomalies", () => {
  const model = new ConversationGraphModel();
  model.reconcile([message("root", null, 100), message("copy", "root", 100), message("peer", "root", 100), message("clock", "copy", -10)], []);
  assert.notEqual(point(model, "copy").x, point(model, "peer").x);
  assert(point(model, "copy").y > point(model, "root").y);
  assert(point(model, "clock").y > point(model, "copy").y);
  assert.equal(model.edges.get("parent:clock").anomaly, true);
  assert.equal(model.edges.get("parent:copy").anomaly, false);
});

test("long clock gaps are compressed and invalid timestamps remain inspectable", () => {
  const model = new ConversationGraphModel();
  model.reconcile([message("old"), message("new", "old", 10 * 365 * 86400), { ...message("bad"), timestamp: "invalid" }], []);
  assert(point(model, "new").y > point(model, "old").y);
  assert(point(model, "new").y - point(model, "old").y < 1000);
  assert(Number.isFinite(point(model, "bad").y));
});

test("late arrivals and reordered message upserts preserve every old position", () => {
  const model = new ConversationGraphModel();
  const original = [message("a"), message("b", "a", 10), message("c", "b", 20)];
  model.reconcile(original, [{ id: "main", head: "c" }]);
  const before = new Map([...model.positions].map(([id, position]) => [id, { ...position }]));
  model.reconcile([message("late", "a", 1), ...original.toReversed(), message("ancient", null, -500)], [{ id: "main", head: "late" }]);
  for (const [id, position] of before) if (id.startsWith("message:")) assert.deepEqual(model.positions.get(id), position);
  const late = { ...point(model, "late") };
  model.reconcile(original, [{ id: "main", head: "late" }]);
  assert.deepEqual(point(model, "late"), late);
  assert(model.nodes.has(m("late"))); // immutable inventory accumulation, not path filtering
});

test("missing parent is identifiable, replaced in place, and late ancestors sit above fixed descendants", () => {
  const model = new ConversationGraphModel();
  model.reconcile([message("child", "missing", 10)], []);
  const reserved = { ...point(model, "missing") }, child = { ...point(model, "child") };
  assert.equal(model.nodes.get(m("missing")).kind, "missing");
  assert.equal(model.nodes.get(m("missing")).rawId, "missing");
  model.reconcile([message("missing", "grandparent", 100)], []);
  assert.equal(model.nodes.get(m("missing")).kind, "message");
  assert.deepEqual(point(model, "missing"), reserved);
  assert.deepEqual(point(model, "child"), child);
  assert(point(model, "grandparent").y < reserved.y);
  assert.equal(model.edges.get("parent:child").anomaly, true);
});

test("session nodes support empty, shared heads, moves, and unreadable versus empty snapshots", () => {
  const model = new ConversationGraphModel();
  model.reconcile([message("a"), message("b", "a", 1)], [{ id: "one", head: "a" }, { id: "two", head: "a" }, { id: "empty", head: null }]);
  assert(model.nodes.has(s("empty")));
  assert(!model.edges.has("head:empty"));
  assert.equal(model.edges.get("head:one").target, model.edges.get("head:two").target);
  model.reconcile([], [{ id: "one", head: "b" }, { id: "two", head: null, error: "missing ancestry" }, { id: "empty", head: null }]);
  assert.equal(model.edges.get("head:one").target, m("b"));
  assert.equal(model.edges.get("head:two").target, m("a"));
  assert.equal(model.nodes.get(s("two")).error, "missing ancestry");
  model.reconcile([], [{ id: "one", head: null }]);
  assert(!model.edges.has("head:one"));
  assert(!model.nodes.has(s("two")));
  assert.equal(model.messages.size, 2);
});

test("session lanes stay stable across listing reorder and missing session heads remain visible", () => {
  const model = new ConversationGraphModel();
  model.reconcile([], [{ id: "z", head: "unknown-head" }, { id: "a", head: null }]);
  const z = { ...model.positions.get(s("z")) }, a = { ...model.positions.get(s("a")) };
  assert.equal(model.nodes.get(m("unknown-head")).kind, "missing");
  assert.equal(model.edges.get("head:z").target, m("unknown-head"));
  model.reconcile([], [{ id: "new", head: null }, { id: "a", head: null }, { id: "z", head: "unknown-head" }]);
  assert.deepEqual(model.positions.get(s("z")), z);
  assert.deepEqual(model.positions.get(s("a")), a);
});

test("search matches IDs, content, roles, authors and session names; empty state works", () => {
  const model = new ConversationGraphModel();
  model.reconcile([], []);
  assert.equal(model.nodes.size, 0);
  assert.deepEqual(model.search(""), []);
  model.reconcile([message("abc", null, 0, "Unusual NEEDLE text")], [{ id: "research", head: "abc" }]);
  for (const query of ["ABC", "needle", "human", "USER"]) assert.equal(model.search(query)[0].rawId, "abc");
  assert.equal(model.search("research")[0].kind, "session");
  assert.equal(model.search("absent").length, 0);
});

test("cycles remain visible without recursion or infinite history walks", () => {
  const model = new ConversationGraphModel();
  model.reconcile([message("a", "b"), message("b", "a")], [{ id: "cycle", head: "a" }]);
  assert.equal(model.history("cycle").size, 2);
  assert([...model.edges.values()].some(edge => edge.anomaly));
});


test("session pointers sit below shared/moved heads in compact non-overlapping slots without moving messages", () => {
  const model = new ConversationGraphModel();
  const messages = [message("root"), message("child", "root", 1), message("new-head", "child", 2)];
  const sessions = [{ id: "a", head: "root" }, { id: "b", head: "root" }, { id: "empty", head: null }];
  model.reconcile(messages, sessions);
  const fixed = new Map(messages.map(node => [node.id, { ...point(model, node.id) }]));
  const check = () => {
    for (const session of model.sessions.values()) {
      if (session.head) assert(model.positions.get(s(session.id)).y > point(model, session.head).y);
    }
    const points = [...model.nodes.keys()].map(id => model.positions.get(id));
    for (let i = 0; i < points.length; i++) for (let j = i + 1; j < points.length; j++) {
      assert(Math.abs(points[i].x - points[j].x) >= 150 || Math.abs(points[i].y - points[j].y) >= 80);
    }
    for (const [id, position] of fixed) assert.deepEqual(point(model, id), position);
  };
  check();
  assert(Math.abs(model.positions.get(s("a")).x - point(model, "root").x) <= 210);
  assert.notDeepEqual(model.positions.get(s("a")), model.positions.get(s("b")));
  model.reconcile([], [{ id: "a", head: "new-head" }, { id: "b", head: "root" }, { id: "empty", head: null }]);
  check();
  assert.equal(model.edges.get("head:a").source, s("a"));
  assert.equal(model.edges.get("head:a").target, m("new-head"));
});

const assertNodeClearance = model => {
  const positions = [...model.nodes.keys()].map(id => model.positions.get(id));
  for (let i = 0; i < positions.length; i++) for (let j = i + 1; j < positions.length; j++) {
    assert(Math.abs(positions[i].x - positions[j].x) >= 150 || Math.abs(positions[i].y - positions[j].y) >= 80);
  }
};
const staggeredForks = count => {
  const messages = [message("spine-0")];
  for (let i = 1; i <= count; i++) {
    messages.push(message(`spine-${i}`, `spine-${i - 1}`, i), message(`twig-${i}`, `spine-${i - 1}`, i));
  }
  return messages;
};

test("finished staggered forks reuse two columns, initially and on append, independent of input order", () => {
  const messages = staggeredForks(60), initial = new ConversationGraphModel(), incremental = new ConversationGraphModel();
  initial.reconcile(messages, []);
  const reordered = new ConversationGraphModel();
  reordered.reconcile(messages.toReversed(), []);
  assert.deepEqual(reordered.positions, initial.positions);
  incremental.reconcile([messages[0]], []);
  for (let i = 1; i < messages.length; i += 2) incremental.reconcile(messages.slice(i, i + 2), []);
  for (const model of [initial, incremental]) {
    const lanes = new Set([...model.positions.values()].map(p => p.x));
    assert.equal(lanes.size, 2, "one permanent column per old twig would grow to 61 columns");
    assertNodeClearance(model);
    for (const edge of model.edges.values()) assert(!edge.layoutWarning);
  }
});

test("long branch spans reserve their lanes, and elbows do not pass through unrelated message labels", () => {
  const model = new ConversationGraphModel();
  model.reconcile([...staggeredForks(8), message("long", "spine-0", 1e9), message("peer", "spine-4", 5)], []);
  assertNodeClearance(model);
  const vertical = [];
  for (const edge of model.edges.values()) {
    const route = model.routes.get(edge.id);
    assert(!route.fallback);
    const segments = ancestrySegments(model.positions.get(edge.source), model.positions.get(edge.target), route.departure);
    segments.forEach(([a, b], index) => {
      for (const [id, p] of model.positions) {
        if (id === edge.source || id === edge.target) continue;
        if (a.x === b.x) assert(Math.abs(a.x - p.x) >= 90 || Math.max(a.y, b.y) <= p.y - 42 || Math.min(a.y, b.y) >= p.y + 42, `${edge.id} vs ${id}`);
        else assert(Math.abs(a.y - p.y) >= 42 || Math.max(a.x, b.x) <= p.x - 90 || Math.min(a.x, b.x) >= p.x + 90, `${edge.id} vs ${id}`);
      }
      if (a.x === b.x && a.y !== b.y) vertical.push({ edge, index, a, b });
    });
  }
  for (const [i, a] of vertical.entries()) for (const b of vertical.slice(i + 1)) {
    if (a.edge.id === b.edge.id || (a.edge.source === b.edge.source && a.index === 0 && b.index === 0)) continue;
    assert(a.a.x !== b.a.x || Math.max(a.a.y, a.b.y) <= Math.min(b.a.y, b.b.y) || Math.max(b.a.y, b.b.y) <= Math.min(a.a.y, a.b.y));
  }
});

test("bounded seeded dense DAG routing terminates, keeps nodes apart and never moves existing messages", { timeout: 5000 }, () => {
  // Seed 1, second generated fixture originally exhausted every single-elbow lane indefinitely.
  let seed = 1;
  const random = n => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed % n; };
  let fallbacks = 0;
  for (let run = 0; run < 200; run++) {
    const model = new ConversationGraphModel(), messages = [];
    for (let i = 0; i < 30; i++) messages.push({ ...message(`m${i}`, i && random(5) ? `m${random(i)}` : null), timestamp: new Date(1e12 + random(100000)).toISOString() });
    model.reconcile(messages, []);
    const fixed = new Map(model.positions);
    for (let i = 30; i < 40; i++) model.reconcile([{ ...message(`m${i}`, `m${random(i)}`), timestamp: new Date(1e12 + random(100000)).toISOString() }], []);
    for (const [id, position] of fixed) assert.deepEqual(model.positions.get(id), position);
    assertNodeClearance(model);
    fallbacks += [...model.edges.values()].filter(edge => edge.layoutWarning).length;
  }
  assert(fallbacks > 0, "exercise the finite routing fallback, independently of timestamp anomalies");
});

test("nearest-fork family includes local siblings but excludes remote cousins and unrelated roots", () => {
  const model = new ConversationGraphModel();
  model.reconcile([message("root"), message("a", "root"), message("remote", "root"), message("fork", "a"),
    message("leaf", "fork"), message("sibling", "fork"), message("sibling-child", "sibling"), message("unrelated")],
    [{ id: "work", head: "leaf" }, { id: "shared", head: "leaf" }, { id: "remote", head: "remote" }, { id: "empty", head: null }]);
  assert.deepEqual(model.visible("siblings", m("leaf")), new Set([m("leaf"), m("fork"), m("a"), m("root"), m("sibling"), m("sibling-child"), s("work"), s("shared")]));
  assert.deepEqual(model.visible("current", m("leaf")), new Set([m("leaf"), m("fork"), m("a"), m("root"), s("work"), s("shared")]));
  assert.deepEqual(model.visible("siblings", m("fork")), model.visible("siblings", m("leaf")));
  assert.deepEqual(model.visible("siblings", null), new Set(model.nodes.keys()));
  assert.deepEqual(model.visible("all", m("leaf")), new Set(model.nodes.keys()));
  assert.deepEqual(model.visible("current", s("empty")), new Set([s("empty")]));
  assert(model.search("remote").some(node => node.id === m("remote")), "search ignores filters");
});

test("both focused filters include every descendant, including nested forks and live appends", () => {
  const model = new ConversationGraphModel();
  model.reconcile([
    message("root"), message("ancestor", "root"), message("remote", "root"), message("fork", "ancestor"),
    message("selected", "fork"), message("sibling", "fork"), message("sibling-child", "sibling"),
    message("child", "selected"), message("left", "child"), message("right", "child"),
    message("grandchild", "left"), message("unrelated")
  ], [{ id: "work", head: "grandchild" }, { id: "alternative", head: "sibling-child" }]);
  const current = new Set(["root", "ancestor", "fork", "selected", "child", "left", "right", "grandchild"].map(m));
  current.add(s("work"));
  assert.deepEqual(model.visible("current", m("selected")), current);
  const family = new Set([...current, m("sibling"), m("sibling-child"), s("alternative")]);
  assert.deepEqual(model.visible("siblings", m("selected")), family);
  const fixed = new Map(model.positions);
  model.reconcile([message("newest", "grandchild")], [{ id: "work", head: "newest" }]);
  for (const mode of ["current", "siblings"]) {
    const visible = model.visible(mode, m("selected"));
    for (const id of ["child", "left", "right", "grandchild", "newest"]) assert(visible.has(m(id)));
    assert(visible.has(s("work")));
    assert(!visible.has(m("remote")));
    assert(!visible.has(m("unrelated")));
  }
  for (const [id, position] of fixed) if (id.startsWith("message:")) assert.deepEqual(model.positions.get(id), position);
});

test("both focused filters show a linear branch below its selected node without requiring a fork", () => {
  const model = new ConversationGraphModel();
  model.reconcile([message("root"), message("middle", "root"), message("child", "middle"), message("tip", "child"), message("unrelated")], [{ id: "work", head: "tip" }]);
  const expected = new Set(["root", "middle", "child", "tip"].map(m));
  expected.add(s("work"));
  for (const mode of ["current", "siblings"]) {
    assert.deepEqual(model.visible(mode, m("middle")), expected);
    assert.deepEqual(model.visible(mode, m("root")), expected);
  }
});

test("session focus follows live heads, missing ancestry and cycles remain filterable without moving inventory", () => {
  const model = new ConversationGraphModel();
  model.reconcile([message("child", "missing"), message("other")], [{ id: "work", head: "child" }, { id: "unknown", head: "absent" }]);
  const fixed = new Map(model.positions);
  assert.deepEqual(model.visible("current", s("work")), new Set([m("child"), m("missing"), s("work")]));
  assert.deepEqual(model.visible("siblings", s("unknown")), new Set([m("absent"), s("unknown")]));
  model.reconcile([], [{ id: "work", head: "other" }]);
  assert.deepEqual(model.visible("current", s("work")), new Set([m("other"), s("work")]));
  for (const [id, p] of fixed) if (id.startsWith("message:")) assert.deepEqual(model.positions.get(id), p);
  model.reconcile([message("cycle", "cycle")], []);
  assert.deepEqual(model.visible("siblings", m("cycle")), new Set([m("cycle")]));
  assert(model.edges.get("parent:cycle").anomaly);
});

test("explicit inventory replacement removes optimistic ghosts but preserves surviving geometry and branches", () => {
  const model = new ConversationGraphModel();
  const stored = { id: "stored", role: "user", timestamp: "2026-01-01T00:00:00Z" };
  const branch = { ...stored, id: "branch", parentId: "stored" };
  const ghost = { ...stored, id: "ghost", parentId: "stored" };
  model.reconcile([stored, branch, ghost], []);
  const position = { ...model.positions.get("message:branch") };
  const graph = model.replaceInventory([stored, branch], []);
  assert(!model.messages.has("ghost"));
  assert(!graph.nodes.has("message:ghost"));
  assert(!model.positions.has("message:ghost"));
  assert(!model.routes.has("parent:ghost"));
  assert.deepEqual(model.positions.get("message:branch"), position);
  assert(model.messages.has("branch"));
});
