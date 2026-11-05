import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";
import { createMarkdownRenderer } from "./markdown.js";

function render(source, options) {
  const dom = new JSDOM("<!doctype html>", { url: "https://example.test/#/sdlc" });
  const fragment = createMarkdownRenderer(dom.window, options)(source);
  const container = dom.window.document.createElement("div");
  container.appendChild(fragment);
  return container;
}

test("Markdown supports headings, emphasis, code, nested lists and tables", () => {
  const body = render("# Plan\n\nBuild **reliable** `code`.\n\n- Parent\n  - Child\n\n```scala\nval x = 1\n```\n\n| A | B |\n| - | - |\n| 1 | 2 |");
  assert.equal(body.querySelector("h1").textContent, "Plan");
  assert.equal(body.querySelector("strong").textContent, "reliable");
  assert.equal(body.querySelector("ul ul li").textContent, "Child");
  assert.equal(body.querySelector("pre code").textContent.trim(), "val x = 1");
  assert.equal(body.querySelectorAll("td").length, 2);
});

test("raw HTML stays literal and images never create network-loading elements", () => {
  const body = render('<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n![tracking](https://tracker.test/pixel)\n\n<svg onload=alert(1)></svg>');
  assert.equal(body.querySelector("script, img, svg, iframe, style"), null);
  assert.match(body.textContent, /<script>alert\(1\)<\/script>/);
  assert.match(body.textContent, /\[Image: tracking\]/);
});

test("unsafe links are stripped and safe links cannot replace the app or access its opener", () => {
  const body = render("[unsafe](javascript:alert%281%29) [data](data:text/html,hello) [safe](https://example.org/docs) [mail](mailto:hello@example.org)");
  const links = [...body.querySelectorAll("a")];
  assert.equal(links[0].getAttribute("href"), null);
  assert.equal(links[1].getAttribute("href"), null);
  assert.equal(links[2].getAttribute("href"), "https://example.org/docs");
  assert.equal(links[3].getAttribute("href"), "mailto:hello@example.org");
  for (const link of links) {
    assert.equal(link.getAttribute("target"), "_blank");
    assert.equal(link.getAttribute("rel"), "noopener noreferrer");
    assert.equal(link.getAttribute("onclick"), null);
  }
});

test("Markdown task lists are display-only and raw controls cannot become interactive", () => {
  const body = render("- [x] Done\n- [ ] Pending\n\n<input autofocus><form><button>submit</button></form>");
  assert.equal(body.querySelector("input, form, button"), null);
  assert.match(body.textContent, /\[x\] Done/);
  assert.match(body.textContent, /\[ \] Pending/);
});

test("code contents are text, not markup", () => {
  const body = render('```html\n<img src=x onerror=alert(1)>\n```');
  assert.equal(body.querySelector("img"), null);
  assert.match(body.querySelector("pre code").textContent, /<img src=x onerror=alert\(1\)>/);
});

test("Scala fences highlight keywords, strings and numbers without changing code text", () => {
  const source = 'case class Task(id: String, done: Boolean)\n\nval message = "hello"\nval count = 42';
  const body = render('```scala\n' + source + '\n```');
  const code = body.querySelector("code.hljs.language-scala");
  assert.equal(code.textContent, source + "\n");
  assert.ok(code.querySelector(".hljs-keyword"));
  assert.ok(code.querySelector(".hljs-string"));
  assert.ok(code.querySelector(".hljs-number"));
});

test("Scala info strings are case-insensitive and never become arbitrary attributes", () => {
  const body = render('```SCALA title="example" onclick="alert(1)"\nval count = 1\n```');
  const code = body.querySelector("code.language-scala");
  assert.ok(code.querySelector(".hljs-keyword"));
  assert.equal(code.getAttribute("onclick"), null);
  assert.equal(code.getAttribute("title"), null);
  assert.equal(body.querySelector("[onclick]"), null);
});

test("unknown and unlabeled fences stay plain code; Mermaid stays available to its enhancer", () => {
  const body = render('```unknown\nval count = 1\n```\n\n```\nval count = 1\n```\n\n```mermaid\nflowchart LR\n A-->B\n```');
  assert.equal(body.querySelectorAll("code").length, 3);
  assert.equal(body.querySelectorAll(".hljs, span").length, 0);
  assert.ok(body.querySelector("code.language-mermaid"));
});

test("HTML-looking Scala strings stay literal after highlighting and sanitization", () => {
  const source = 'val html = "<img src=x onerror=alert(1)>"\n// <script>alert(1)</script>';
  const body = render('```scala\n' + source + '\n```');
  assert.ok(body.querySelector(".hljs-string"));
  assert.equal(body.querySelector("code").textContent, source + "\n");
  assert.equal(body.querySelector("img, script, iframe, style, [onerror]"), null);
});

test("oversized Scala blocks safely fall back to plain code", () => {
  const source = "// " + "x".repeat(50001);
  const body = render('```scala\n' + source + '\n```');
  assert.equal(body.querySelector("code").textContent, source + "\n");
  assert.equal(body.querySelector("span, .hljs"), null);
});

test("tool mode preserves literal Markdown, indentation, line breaks, JSON and unsafe HTML", () => {
  const source = '# literal heading\n*not emphasis*\n    indented log\n\n{"path":"C:\\\\tmp", "n":1}\n<img src=x onerror=alert(1)>\n';
  const body = render(source, { fencedOnly: true });
  assert.equal(body.querySelector("pre").textContent, source);
  assert.equal(body.querySelector("h1, em, img, [onerror]"), null);
});

test("tool mode highlights fences between logs and keeps Mermaid blocks marked", () => {
  const before = 'compiler output\n  keep these spaces\n\n';
  const after = '\n\nexit: 0\n';
  const body = render(before + '~~~scala\nval count = 42\n~~~' + after + '\n```mermaid\nflowchart LR\n A-->B\n```', { fencedOnly: true });
  assert.equal(body.querySelector("pre").textContent, before);
  assert.ok(body.querySelector("code.language-scala .hljs-keyword"));
  assert.ok(body.querySelector("code.language-mermaid"));
  assert.ok([...body.querySelectorAll("pre")].some(pre => pre.textContent === after + "\n"));
});

test("indented tool output remains literal rather than losing its leading spaces", () => {
  const source = '    one\n      two\n';
  const body = render(source, { fencedOnly: true });
  assert.equal(body.querySelector("pre").textContent, source);
  assert.equal(body.querySelector("code"), null);
});

test("outer Markdown example fences do not accidentally render their nested examples", () => {
  const source = '````markdown\n```scala\nval count = 1\n```\n````';
  for (const fencedOnly of [true, false]) {
    const body = render(source, { fencedOnly });
    assert.equal(body.querySelectorAll("pre code").length, 1);
    assert.equal(body.querySelector("code").textContent, '```scala\nval count = 1\n```\n');
    assert.equal(body.querySelector("span, .hljs"), null);
  }
});
