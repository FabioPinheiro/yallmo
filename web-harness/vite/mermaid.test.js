import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";
import { createMarkdownRenderer } from "./markdown.js";
import { createMermaidEnhancer } from "./mermaid.js";

const tick = () => new Promise(resolve => setImmediate(resolve));
const diagram = "```mermaid\nflowchart LR\n A[User] --> B[Agent]\n```";
const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50"><text x="0" y="20">User → Agent</text></svg>';

function fixture(source, overrides = {}) {
  const dom = new JSDOM("<!doctype html><body></body>", { url: "https://example.test/#/sdlc" });
  const host = dom.window.document.createElement("div");
  host.appendChild(createMarkdownRenderer(dom.window)(source));
  dom.window.document.body.appendChild(host);
  const calls = [];
  const mermaid = {
    initialize: config => calls.push(["initialize", config]),
    render: async (id, source) => { calls.push(["render", id, source]); return { svg }; },
    ...overrides
  };
  return { dom, host, calls, mermaid };
}

test("only Mermaid fences are marked; raw HTML cannot forge a diagram", () => {
  const { host } = fixture('```scala\nval n = 1\n```\n\n' + diagram + '\n\n<pre><code class="language-mermaid">bad</code></pre>');
  assert.equal(host.querySelectorAll("code.language-mermaid").length, 1);
  assert.equal(host.querySelectorAll("pre").length, 2);
});

test("plain Markdown does not load Mermaid", async () => {
  const { dom, host } = fixture("# Plain\n\n```json\n{}\n```");
  createMermaidEnhancer(dom.window, () => { assert.fail("must not load"); })(host);
  await tick();
  assert.equal(host.querySelector("figure"), null);
});

test("multiple diagrams render as inert images with unique IDs and readable source", async () => {
  const { dom, host, calls, mermaid } = fixture(diagram + "\n\n" + diagram);
  let loads = 0;
  createMermaidEnhancer(dom.window, async () => { loads++; return { default: mermaid }; })(host);
  await tick();
  assert.equal(loads, 1);
  assert.equal(host.querySelectorAll("figure > img").length, 2);
  assert.equal(host.querySelectorAll("details pre code").length, 2);
  assert.match(host.querySelector("details code").textContent, /A\[User\] --> B\[Agent\]/);
  assert.equal(host.querySelector("summary").textContent, "Diagram source");
  assert.match(host.querySelector("img").src, /^data:image\/svg\+xml/);
  assert.equal(host.querySelector("svg"), null);
  const renders = calls.filter(([kind]) => kind === "render");
  assert.notEqual(renders[0][1], renders[1][1]);
  assert.equal(calls[0][1].securityLevel, "strict");
  assert.equal(calls[0][1].htmlLabels, false);
  assert.equal(dom.window.document.body.children.length, 1, "temporary render hosts removed");
});

test("SVG is sanitized, never injected, and Mermaid callbacks are never bound", async () => {
  const { dom, host, mermaid } = fixture(diagram, {
    render: async () => ({
      svg: '<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"><script>alert(1)</script><foreignObject><iframe src="https://evil.test"/></foreignObject><image href="https://evil.test/pixel"/><a href="https://evil.test"><text>Label</text></a></svg>',
      bindFunctions: () => assert.fail("must never bind callbacks")
    })
  });
  createMermaidEnhancer(dom.window, async () => ({ default: mermaid }))(host);
  await tick();
  const rendered = decodeURIComponent(host.querySelector("img").src.split(",")[1]);
  assert.doesNotMatch(rendered, /script|onload|foreignObject|iframe|<image|<a\b/);
  assert.equal(host.querySelector("svg, iframe, script, a"), null);
});

test("bad diagrams keep source, clean up scratch DOM, and do not block later diagrams", async () => {
  let count = 0;
  const { dom, host, mermaid } = fixture(diagram + "\n\n" + diagram, {
    render: async (_id, _source, scratch) => {
      scratch.textContent = "temporary error";
      if (++count === 1) throw new Error("parse error");
      return { svg };
    }
  });
  createMermaidEnhancer(dom.window, async () => ({ default: mermaid }))(host);
  await tick();
  assert.match(host.querySelector("[role=status]").textContent, /Diagram unavailable/);
  assert.equal(host.querySelectorAll("pre code").length, 2);
  assert.equal(host.querySelectorAll("figure").length, 1);
  assert.equal(dom.window.document.body.children.length, 1);
});

test("failed library import preserves source", async () => {
  const { dom, host } = fixture(diagram);
  createMermaidEnhancer(dom.window, async () => { throw new Error("offline"); })(host);
  await tick();
  assert.equal(host.querySelector("img"), null);
  assert.ok(host.querySelector("pre code"));
  assert.ok(host.querySelector("[role=status]"));
});

test("configuration directives, frontmatter and oversized diagrams remain text", async () => {
  for (const source of ['%%{init: {"securityLevel":"loose"}}%%\nflowchart LR\n A-->B', '---\nconfig:\n  theme: dark\n---\nflowchart LR\n A-->B', "x".repeat(50001)]) {
    const { dom, host } = fixture("```mermaid\n" + source + "\n```");
    createMermaidEnhancer(dom.window, () => { assert.fail("configuration must not reach Mermaid"); })(host);
    await tick();
    assert.ok(host.querySelector("[role=status]"));
    assert.equal(host.querySelector("img"), null);
  }
});

test("resource-bearing HTML, images and custom CSS never reach the live renderer", async () => {
  for (const source of [
    'flowchart LR\n A@{ img: "https://tracker.test/pixel" }',
    'flowchart LR\n A["<img src=https://tracker.test/pixel>"]',
    'flowchart LR\n A-->B\n classDef external fill:url(https://tracker.test/pixel)',
    'flowchart LR\n A-->B\n style A fill:red',
    'flowchart LR\n A["\\\\75rl"]'
  ]) {
    const { dom, host } = fixture("```mermaid\n" + source + "\n```");
    createMermaidEnhancer(dom.window, () => { assert.fail("resources must not reach Mermaid"); })(host);
    await tick();
    assert.ok(host.querySelector("[role=status]"));
    assert.equal(host.querySelector("img"), null);
  }
});

test("unmount while importing prevents rendering and late DOM changes", async () => {
  const { dom, host, calls, mermaid } = fixture(diagram);
  let resolve;
  const pending = new Promise(done => { resolve = done; });
  const cancel = createMermaidEnhancer(dom.window, () => pending)(host);
  const before = host.innerHTML;
  cancel();
  host.remove();
  resolve({ default: mermaid });
  await tick();
  assert.equal(host.innerHTML, before);
  assert.equal(calls.filter(([kind]) => kind === "render").length, 0);
});

test("unmount during rendering ignores output and skips remaining diagrams", async () => {
  let resolve;
  let renders = 0;
  const pending = new Promise(done => { resolve = done; });
  const { dom, host, mermaid } = fixture(diagram + "\n\n" + diagram, {
    render: () => { renders++; return pending; }
  });
  const cancel = createMermaidEnhancer(dom.window, async () => ({ default: mermaid }))(host);
  await tick();
  cancel();
  const before = host.innerHTML;
  resolve({ svg });
  await tick();
  assert.equal(host.innerHTML, before);
  assert.equal(renders, 1);
  assert.equal(dom.window.document.body.children.length, 1);
});

test("image load failure exposes the source", async () => {
  const { dom, host, mermaid } = fixture(diagram);
  createMermaidEnhancer(dom.window, async () => ({ default: mermaid }))(host);
  await tick();
  host.querySelector("img").dispatchEvent(new dom.window.Event("error"));
  assert.equal(host.querySelector("img"), null);
  assert.equal(host.querySelector("details").open, true);
  assert.match(host.querySelector("summary").textContent, /unavailable/);
});

test("Mermaid resolves the application's dark palette into standalone SVG styling", async () => {
  const { dom, host, calls, mermaid } = fixture(diagram);
  const palette = dom.window.document.documentElement.style;
  palette.setProperty("--background", "#111827");
  palette.setProperty("--surface-selected", "#243d5e");
  palette.setProperty("--text", "#f1f5f9");
  palette.setProperty("--accent", "#78b3ff");
  createMermaidEnhancer(dom.window, async () => ({ default: mermaid }))(host);
  await tick();
  const config = calls.find(([kind]) => kind === "initialize")[1];
  assert.equal(config.theme, "base");
  assert.equal(config.themeVariables.darkMode, true);
  assert.equal(config.themeVariables.background, "#111827");
  assert.equal(config.themeVariables.primaryColor, "#243d5e");
  assert.equal(config.themeVariables.primaryTextColor, "#f1f5f9");
  assert.equal(config.themeVariables.actorTextColor, "#f1f5f9");
  assert.equal(config.themeVariables.signalTextColor, "#f1f5f9");
  assert.equal(config.themeVariables.primaryBorderColor, "#78b3ff");
});

test("percentage-sized Mermaid SVGs get intrinsic dimensions from their viewBox", async () => {
  const { dom, host, mermaid } = fixture(diagram, {
    render: async () => ({ svg: '<svg xmlns="http://www.w3.org/2000/svg" width="100%" style="max-width: 180px; width: 100%; height: auto; background: transparent" viewBox="-5 -10 180 720"><text>Vertical</text></svg>' })
  });
  createMermaidEnhancer(dom.window, async () => ({ default: mermaid }))(host);
  await tick();
  const source = decodeURIComponent(host.querySelector("img").src.split(",")[1]);
  const root = new dom.window.DOMParser().parseFromString(source, "image/svg+xml").documentElement;
  assert.equal(root.getAttribute("width"), "180");
  assert.equal(root.getAttribute("height"), "720");
  assert.equal(root.getAttribute("viewBox"), "-5 -10 180 720");
  assert.equal(root.style.width, "");
  assert.equal(root.style.maxWidth, "");
  assert.equal(root.style.height, "");
  assert.equal(root.style.background, "transparent");
});

test("diagram expansion toggles fit mode without changing its source or image boundary", async () => {
  const { dom, host, mermaid } = fixture(diagram);
  const cancel = createMermaidEnhancer(dom.window, async () => ({ default: mermaid }))(host);
  await tick();
  const figure = host.querySelector("figure");
  const button = figure.querySelector("button");
  const image = figure.querySelector("img");
  const source = figure.querySelector("code").textContent;
  assert.equal(button.getAttribute("aria-expanded"), "false");
  button.click();
  assert.equal(figure.classList.contains("is-expanded"), true);
  assert.equal(button.textContent, "Fit diagram");
  assert.equal(button.getAttribute("aria-expanded"), "true");
  button.click();
  assert.equal(figure.classList.contains("is-expanded"), false);
  assert.equal(button.textContent, "Expand diagram");
  assert.equal(figure.querySelector("code").textContent, source);
  assert.equal(figure.querySelector("img"), image);
  assert.equal(host.querySelector("svg"), null);
  cancel();
  button.click();
  assert.equal(figure.classList.contains("is-expanded"), false, "cancelled hosts ignore late controls");
});
