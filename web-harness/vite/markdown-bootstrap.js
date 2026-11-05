import { createMarkdownRenderer } from "./markdown.js";

// Evaluated before the Scala.js entry point, following the voice bridge pattern.
globalThis.yallmoMarkdown = { render: createMarkdownRenderer(window) };
