import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";
import { createMarkdownRenderer } from "./markdown.js";

function render(source) {
  const dom = new JSDOM("<!doctype html>", { url: "https://example.test/#/sdlc" });
  const fragment = createMarkdownRenderer(dom.window)(source);
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
