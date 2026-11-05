import { createMarkdownRenderer } from "./markdown.js";
import { createMermaidEnhancer } from "./mermaid.js";

// Evaluated before the Scala.js entry point, following the voice bridge pattern.
globalThis.yallmoMarkdown = {
  render: createMarkdownRenderer(window),
  renderBlocks: createMarkdownRenderer(window, { fencedOnly: true }),
  enhance: createMermaidEnhancer(window)
};
