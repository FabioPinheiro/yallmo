import test from "node:test";
import assert from "node:assert/strict";
import cytoscape from "cytoscape";
import { JSDOM } from "jsdom";
import { ConversationGraphModel, messageKey as m } from "./conversation-graph-model.js";
import { patchGraph, applyGraphVisibility, GraphSessionNavigation } from "./conversation-graph.js";
import { GraphInspector } from "./conversation-graph-inspector.js";
import { GraphFilter, GraphSearch } from "./conversation-graph-search.js";

const message = (id, parentId = null) => ({ id, parentId, role: "user", content: "hello", timestamp: "2026-01-01T00:00:00Z" });

test("headless Cytoscape patches preserve selection, pan, zoom and positions, and move session edges", () => {
  const cy = cytoscape({ headless: true });
  try {
    const model = new ConversationGraphModel();
    patchGraph(cy, model.reconcile([message("a"), message("b", "a")], [{ id: "main", head: "a" }]));
    const a = cy.getElementById(m("a"));
    a.select();
    const before = { ...a.position() };
    cy.pan({ x: 17, y: 34 });
    cy.zoom(1.4);
    const pan = { ...cy.pan() }, zoom = cy.zoom();
    patchGraph(cy, model.reconcile([message("c", "a")], [{ id: "main", head: "b" }]));
    assert.equal(cy.getElementById(m("a")), a);
    assert(a.selected());
    assert.deepEqual(a.position(), before);
    assert.deepEqual(cy.pan(), pan);
    assert.equal(cy.zoom(), zoom);
    assert.equal(cy.getElementById("head:main").target().id(), m("b"));
    let mutations = 0;
    const changed = () => { mutations++; };
    cy.on("add remove data position", changed);
    patchGraph(cy, model.reconcile([], [{ id: "main", head: "b" }]));
    assert.equal(mutations, 0, "identical refresh must not mutate renderer elements");
    cy.off("add remove data position", changed);
    patchGraph(cy, model.reconcile([], []));
    assert(cy.getElementById("session:main").empty());
    assert.equal(cy.nodes().length, 3);
  } finally { cy.destroy(); }
});

test("Cytoscape replaces missing placeholder in place without dropping incident edges", () => {
  const cy = cytoscape({ headless: true });
  try {
    const model = new ConversationGraphModel();
    patchGraph(cy, model.reconcile([message("b", "a")], []));
    const placeholder = cy.getElementById(m("a"));
    const before = { ...placeholder.position() };
    patchGraph(cy, model.reconcile([message("a")], []));
    assert.equal(cy.getElementById(m("a")), placeholder);
    assert.equal(placeholder.data("kind"), "message");
    assert.deepEqual(placeholder.position(), before);
    assert.equal(cy.edges().length, 1);
  } finally { cy.destroy(); }
});

test("session navigation queues only the latest pre-snapshot request and focuses repeated selections", () => {
  const available = new Set(), focused = [], selected = [];
  const navigation = new GraphSessionNavigation(id => available.has(id), id => focused.push(id), id => selected.push(id));
  navigation.request("old");
  navigation.request("latest");
  assert.deepEqual(focused, []);
  available.add("old");
  navigation.reconcile();
  assert.deepEqual(focused, []);
  available.add("latest");
  navigation.reconcile();
  assert.deepEqual(focused, ["latest"]);
  assert.equal(navigation.pending, null);
  navigation.reconcile();
  navigation.reconcile();
  assert.deepEqual(focused, ["latest"], "manual snapshots must not re-focus the selected session");
  navigation.request("latest");
  assert.deepEqual(focused, ["latest", "latest"], "same-ID clicks are actions, not distinct state changes");
  assert.deepEqual(selected, ["old", "latest"]);
});

test("empty/unreadable sessions navigate without a head and unknown sessions wait safely", () => {
  const model = new ConversationGraphModel(), focused = [];
  const navigation = new GraphSessionNavigation(id => model.sessions.has(id), id => focused.push(id));
  model.reconcile([], [{ id: "empty", head: null }, { id: "unreadable", head: null, error: "unavailable" }]);
  navigation.request("empty");
  navigation.request("unreadable");
  navigation.request("unknown");
  navigation.reconcile();
  assert.deepEqual(focused, ["empty", "unreadable"]);
  assert.equal(navigation.pending, "unknown");
  model.reconcile([], [{ id: "unknown", head: null }]);
  navigation.reconcile();
  assert.deepEqual(focused, ["empty", "unreadable", "unknown"]);
  model.reconcile([], []);
  navigation.reconcile();
  assert.equal(navigation.selected, null);
});

test("canvas/search selection cancels queued sidebar requests and reports shared selection", () => {
  const available = new Set(), focused = [], selected = [];
  const navigation = new GraphSessionNavigation(id => available.has(id), id => focused.push(id), id => selected.push(id));
  navigation.request("pending-sidebar");
  navigation.select("canvas-session");
  available.add("pending-sidebar");
  available.add("canvas-session");
  navigation.reconcile();
  assert.deepEqual(focused, []);
  assert.equal(navigation.selected, "canvas-session");
  navigation.select(null);
  assert.deepEqual(selected, ["pending-sidebar", "canvas-session", undefined]);
  assert.equal(navigation.pending, null);
});

test("one safe inspector replaces its body, preserves ordinary refresh, and unmounts on outside/Escape/destroy", () => {
  const dom = new JSDOM("<div id='layer'></div><button id='outside'>Outside</button>");
  const document = dom.window.document, layer = document.getElementById("layer");
  let mounts = 0, disposed = 0, interactions = 0, returned = 0;
  const inspector = new GraphInspector(layer, () => ({ x: 50, y: 50 }), (id, host) => {
    mounts++;
    const button = document.createElement("button");
    button.textContent = id;
    button.addEventListener("click", () => interactions++);
    host.append(button);
    return () => { disposed++; };
  }, () => {}, () => { returned++; });
  const first = { id: m("<script>"), rawId: "<script>", kind: "message" };
  inspector.open(first);
  assert.equal(layer.querySelector("script"), null);
  inspector.card.body.firstChild.dispatchEvent(new dom.window.MouseEvent("pointerdown", { bubbles: true }));
  inspector.card.body.firstChild.click();
  assert.equal(interactions, 1);
  assert(inspector.card);
  inspector.update({ ...first });
  assert.equal(mounts, 1);
  inspector.open({ id: m("second"), rawId: "second", kind: "message" });
  assert.equal(layer.children.length, 1);
  assert.equal(disposed, 1);
  document.getElementById("outside").dispatchEvent(new dom.window.MouseEvent("pointerdown", { bubbles: true }));
  assert.equal(inspector.card, null);
  assert.equal(disposed, 2);
  // An outside pointer-down followed by node activation must open, not immediately dismiss, the replacement.
  inspector.open(first);
  const escape = new dom.window.KeyboardEvent("keydown", { key: "Escape", cancelable: true });
  document.dispatchEvent(escape);
  assert(escape.defaultPrevented);
  assert.equal(inspector.card, null);
  assert.equal(returned, 1);
  inspector.open(first);
  inspector.destroy();
  assert.equal(mounts, disposed);
  document.dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: "Escape" }));
  assert.equal(returned, 1, "document listeners must be removed");
  dom.window.close();
});

test("dragging inside does not dismiss; offsets follow nodes and reopening/keyboard recovery never scrolls the layer", () => {
  const dom = new JSDOM("<div id='layer'></div>");
  const layer = dom.window.document.getElementById("layer");
  Object.defineProperties(layer, { clientWidth: { value: 600 }, clientHeight: { value: 400 } });
  let anchor = { x: 100, y: 100 };
  const inspector = new GraphInspector(layer, () => anchor, () => () => {}, () => {}, () => {});
  const node = { id: m("first"), rawId: "first", kind: "message" };
  inspector.open(node);
  const card = inspector.card, left = parseFloat(card.shell.style.left);
  card.shell.querySelector("header").dispatchEvent(new dom.window.MouseEvent("pointerdown", { button: 0, clientX: 10, clientY: 10, bubbles: true }));
  dom.window.dispatchEvent(new dom.window.MouseEvent("pointermove", { clientX: 40, clientY: 50 }));
  dom.window.dispatchEvent(new dom.window.MouseEvent("pointerup"));
  assert.equal(inspector.card, card);
  assert.equal(parseFloat(card.shell.style.left), left + 30);
  anchor = { x: 110, y: 120 };
  inspector.updatePosition();
  assert.equal(parseFloat(card.shell.style.left), left + 40);
  anchor = { x: 2000, y: 2000 };
  inspector.updatePosition();
  card.shell.scrollIntoView = () => assert.fail("must not scroll a clipped child");
  inspector.open(node);
  assert(parseFloat(card.shell.style.left) >= 8 && parseFloat(card.shell.style.left) <= 152);
  assert(parseFloat(card.shell.style.top) >= 8 && parseFloat(card.shell.style.top) < 400);
  assert.equal(inspector.card, card);
  inspector.destroy();
  dom.window.close();
});

test("mobile inspector recovery uses the final navigation anchor without clamping later pan updates", () => {
  const dom = new JSDOM("<div id='layer'></div>");
  const layer = dom.window.document.getElementById("layer");
  Object.defineProperties(layer, { clientWidth: { value: 358 }, clientHeight: { value: 420 } });
  let anchor = { x: -300, y: 900 };
  const inspector = new GraphInspector(layer, () => anchor, () => () => {}, () => {}, () => {});
  inspector.open({ id: m("hidden-search-result"), rawId: "hidden-search-result", kind: "message" });
  const card = inspector.card;
  // Search center/session fit happens after open; recover against that final rendered position.
  anchor = { x: 179, y: 210 };
  inspector.updatePosition();
  assert(parseFloat(card.shell.style.left) > layer.clientWidth);
  inspector.recover();
  assert.equal(parseFloat(card.shell.style.left), 8);
  assert(parseFloat(card.shell.style.top) >= 8 && parseFloat(card.shell.style.top) <= 232);
  const left = parseFloat(card.shell.style.left), top = parseFloat(card.shell.style.top);
  anchor = { x: 779, y: 810 };
  inspector.updatePosition();
  assert.equal(parseFloat(card.shell.style.left), left + 600, "ordinary pan still follows the node without recovery");
  assert.equal(parseFloat(card.shell.style.top), top + 600);
  assert.equal(inspector.card, card);
  inspector.destroy();
  dom.window.close();
});

test("placeholder/session updates reuse the single shell and maintain safe content", () => {
  const dom = new JSDOM("<div id='layer'></div>");
  let disposed = 0;
  const inspector = new GraphInspector(dom.window.document.getElementById("layer"), () => ({ x: 0, y: 0 }), () => () => { disposed++; }, () => {}, () => {});
  inspector.open({ id: m("missing"), rawId: "missing", kind: "missing" });
  const shell = inspector.card.shell;
  inspector.update({ id: m("missing"), rawId: "missing", kind: "message" });
  assert.equal(inspector.card.shell, shell);
  inspector.open({ id: "session:main", rawId: "main", kind: "session", head: null });
  assert.equal(disposed, 1);
  inspector.update({ id: "session:main", rawId: "main", kind: "session", head: "new-head" });
  assert(inspector.card.body.textContent.includes("new-head"));
  inspector.destroy();
  dom.window.close();
});

test("search toolbar retains accessible Live toggle and only exposes keyboard results for nonempty queries", () => {
  const dom = new JSDOM("<div></div>");
  const model = new ConversationGraphModel(), opened = [];
  model.reconcile([message("message-id")], [{ id: "research", head: null }]);
  const live = [];
  const search = new GraphSearch(dom.window.document, query => model.search(query), id => opened.push(id), value => live.push(value));
  dom.window.document.body.append(search.root);
  assert(search.root.classList.contains("conversation-header"), "graph controls reuse the Chat header layout");
  assert.equal(search.root.firstChild.firstChild, search.input);
  assert.equal(search.root.lastChild.textContent, "Live");
  assert(search.live.checked);
  assert.equal(search.root.querySelectorAll("button").length, 0);
  assert(search.results.hidden);
  search.input.value = "research";
  search.input.dispatchEvent(new dom.window.Event("input"));
  const result = search.results.querySelector("button");
  assert.equal(result.textContent, "session: research");
  result.focus();
  assert.equal(dom.window.document.activeElement, result);
  search.update();
  assert.equal(search.results.querySelector("button"), result, "unchanged snapshots retain a focused search result");
  result.click();
  assert.deepEqual(opened, ["session:research"]);
  assert.equal(search.input.value, "");
  assert(search.results.hidden);
  search.input.value = "hello";
  search.update();
  search.results.querySelector("button").click();
  assert.deepEqual(opened, ["session:research", m("message-id")]);
  search.input.value = "no-such-text";
  search.update();
  assert(search.results.textContent.includes("No matches"));
  search.input.value = "";
  search.update();
  assert(search.results.hidden);
  search.live.click();
  assert.deepEqual(live, [false]);
  search.update();
  assert.equal(search.live.checked, false);
  search.live.click();
  assert.deepEqual(live, [false, true]);
  dom.window.close();
});

test("Live and branch controls do not dismiss a persistent inspector as outside clicks", () => {
  const dom = new JSDOM("<div id='layer'></div><label class='graph-live'><input type='checkbox'>Live</label><label class='graph-filter'><select><option>All</option></select></label><button id='outside'>Outside</button>");
  const document = dom.window.document;
  const inspector = new GraphInspector(document.getElementById("layer"), () => ({ x: 0, y: 0 }), () => () => {}, () => {}, () => {});
  inspector.open({ id: m("a"), rawId: "a", kind: "message" });
  const card = inspector.card;
  for (const selector of [".graph-live input", ".graph-filter select"]) {
    document.querySelector(selector).dispatchEvent(new dom.window.MouseEvent("pointerdown", { bubbles: true }));
    assert.equal(inspector.card, card);
  }
  document.getElementById("outside").dispatchEvent(new dom.window.MouseEvent("pointerdown", { bubbles: true }));
  assert.equal(inspector.card, null);
  inspector.destroy();
  dom.window.close();
});

test("inspector destroy removes every document/window listener", () => {
  const dom = new JSDOM("<div id='layer'></div>");
  const listeners = [];
  for (const [target, types] of [[dom.window.document, ["pointerdown", "keydown"]], [dom.window, ["pointermove", "pointerup", "pointercancel"]]]) {
    const add = target.addEventListener.bind(target), remove = target.removeEventListener.bind(target);
    target.addEventListener = (type, listener, options) => {
      if (types.includes(type)) listeners.push({ target, type, listener, options });
      add(type, listener, options);
    };
    target.removeEventListener = (type, listener, options) => {
      if (types.includes(type)) {
        const index = listeners.findIndex(entry => entry.target === target && entry.type === type && entry.listener === listener && entry.options === options);
        if (index >= 0) listeners.splice(index, 1);
      }
      remove(type, listener, options);
    };
  }
  const inspector = new GraphInspector(dom.window.document.getElementById("layer"), () => ({ x: 0, y: 0 }), () => () => {}, () => {}, () => {});
  assert.equal(listeners.length, 5);
  inspector.destroy();
  assert.equal(listeners.length, 0);
  dom.window.close();
});

test("visibility patches keep hidden elements, coordinates, selection and viewport across filters and snapshots", () => {
  const cy = cytoscape({ headless: true, styleEnabled: true, style: [{ selector: ".filtered", style: { display: "none" } }] });
  try {
    const model = new ConversationGraphModel();
    patchGraph(cy, model.reconcile([message("root"), message("a", "root"), message("b", "root"), message("other")], [{ id: "work", head: "a" }]));
    const a = cy.getElementById(m("a")), b = cy.getElementById(m("b"));
    a.select();
    const before = cy.nodes().map(node => [node.id(), { ...node.position() }]);
    cy.pan({ x: 12, y: 23 }); cy.zoom(1.2);
    const pan = { ...cy.pan() }, zoom = cy.zoom();
    applyGraphVisibility(cy, model.visible("current", m("a")));
    assert.equal(b.style("display"), "none");
    assert.equal(cy.getElementById("parent:b").style("display"), "none");
    assert.equal(cy.getElementById("head:work").style("display"), "element");
    patchGraph(cy, model.reconcile([message("new", "b")], [{ id: "work", head: "a" }]));
    applyGraphVisibility(cy, model.visible("current", m("a")));
    assert(cy.getElementById(m("new")).hasClass("filtered"));
    applyGraphVisibility(cy, model.visible("all", m("a")));
    assert.equal(cy.getElementById(m("b")), b);
    assert.equal(cy.getElementById(m("a")), a);
    assert(a.selected());
    assert.equal(cy.elements(".filtered").length, 0);
    for (const [id, position] of before) assert.deepEqual(cy.getElementById(id).position(), position);
    assert.deepEqual(cy.pan(), pan); assert.equal(cy.zoom(), zoom);
  } finally { cy.destroy(); }
});

test("native branch filter defaults to current siblings and retains All across ordinary renders", () => {
  const dom = new JSDOM("<div></div>");
  const changes = [], filter = new GraphFilter(dom.window.document, () => changes.push(filter.select.value));
  assert.equal(filter.root.textContent, "BranchesAllCurrentCurrent + siblings");
  assert.equal(filter.select.value, "siblings");
  filter.select.value = "all";
  filter.select.dispatchEvent(new dom.window.Event("change"));
  assert.deepEqual(changes, ["all"]);
  assert.equal(filter.select.value, "all");
  assert.equal(filter.select.closest("label"), filter.root);
  dom.window.close();
});

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
test("hover inspector is passive, node-to-card union/focus survive leave, clicked cards persist and timers dispose", async () => {
  const dom = new JSDOM("<div id='layer'></div><button id='outside'>Outside</button>");
  const document = dom.window.document, layer = document.getElementById("layer");
  let focused = 0, disposed = 0;
  const inspector = new GraphInspector(layer, () => ({ x: 10, y: 10 }), (_, host) => {
    host.append(document.createElement("button")); return () => disposed++;
  }, () => focused++, () => focused++);
  const a = { id: m("a"), rawId: "a", kind: "message" }, b = { id: m("b"), rawId: "b", kind: "message" };
  document.getElementById("outside").focus();
  inspector.preview(a);
  assert.equal(inspector.card.persistent, false);
  assert.equal(document.activeElement.id, "outside", "hover never steals focus");
  assert(!inspector.card.shell.textContent.includes("Drag"));
  inspector.leaveNode(a.id);
  inspector.card.shell.dispatchEvent(new dom.window.Event("pointerenter"));
  await delay(150);
  assert(inspector.card, "moving into the card cancels node-leave dismissal");
  inspector.card.body.firstChild.focus();
  inspector.card.shell.dispatchEvent(new dom.window.Event("pointerleave"));
  await delay(150);
  assert(inspector.card, "keyboard interaction retains passive preview");
  document.getElementById("outside").focus();
  await delay(150);
  assert.equal(inspector.card, null);
  inspector.preview(a);
  inspector.leaveNode(a.id);
  await delay(150);
  assert.equal(inspector.card, null, "leaving both closes passive card");
  inspector.preview(a);
  const shell = inspector.card.shell;
  inspector.open(a);
  inspector.leaveNode(a.id);
  inspector.preview(b);
  await delay(150);
  assert.equal(inspector.card.shell, shell);
  assert.equal(inspector.card.id, a.id, "unrelated hover cannot replace click selection");
  inspector.dismissPreview();
  assert(inspector.card);
  document.dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: "Escape" }));
  assert.equal(inspector.card, null);
  inspector.preview(b);
  inspector.dismissPreview();
  assert.equal(inspector.card, null, "pan/filter can dismiss a passive card");
  inspector.preview(a);
  inspector.leaveNode(a.id);
  assert.notEqual(inspector.hoverTimer, null);
  inspector.destroy();
  assert.equal(inspector.hoverTimer, null);
  await delay(150);
  assert.equal(inspector.card, null);
  assert.equal(focused, 1, "only explicit Escape returns focus; hover invokes no navigation callback");
  assert.equal(disposed, 5);
  dom.window.close();
});
