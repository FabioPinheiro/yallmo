import createDOMPurify from "dompurify";
import { Marked } from "marked";
import hljs from "highlight.js/lib/core";
import scala from "highlight.js/lib/languages/scala";

// Explicit languages only: no automatic guessing or full-language bundle.
hljs.registerLanguage("scala", scala);

const escapeHtml = text => text.replace(/[&<>"']/g, character => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
})[character]);

// The returned fragment is the only HTML boundary. Raw HTML remains literal,
// embedded images never load, and sanitizer policy excludes controls, CSS, IDs,
// scripts, SVG and other active content.
export function createMarkdownRenderer(window, { fencedOnly = false } = {}) {
  const purifier = createDOMPurify(window);
  const renderCode = ({ text, lang }) => {
    const language = lang?.trim().split(/\s+/)[0].toLowerCase();
    let content = escapeHtml(text);
    let classes = language === "mermaid" ? "language-mermaid" : "";
    if (language === "scala" && text.length <= 50000) {
      try {
        content = hljs.highlight(text, { language: "scala", ignoreIllegals: true }).value;
        classes = "hljs language-scala";
      } catch {
        // A highlighting failure must not hide or alter the original code.
      }
    }
    return `<pre><code${classes ? ` class="${classes}"` : ""}>${content}\n</code></pre>\n`;
  };
  const parser = new Marked({
    gfm: true,
    renderer: {
      code: renderCode,
      html: ({ text }) => escapeHtml(text),
      image: ({ text }) => escapeHtml(`[Image: ${text}]`),
      checkbox: ({ checked }) => checked ? "[x] " : "[ ] "
    }
  });
  // Tool output keeps its whitespace and literal Markdown outside code blocks.
  // Use Marked's lexer rather than a fence regex (lengths/backticks/tilde fences).
  const renderBlocks = source => {
    let plain = "";
    let html = "";
    const flush = () => {
      // HTML discards one newline immediately after <pre>; supply that newline
      // ourselves so leading blank lines in tool output survive parsing.
      if (plain) html += `<pre>\n${escapeHtml(plain)}</pre>`;
      plain = "";
    };
    for (const token of parser.lexer(source)) {
      if (token.type === "code" && token.codeBlockStyle !== "indented") {
        flush();
        html += renderCode(token);
      } else {
        plain += token.raw;
      }
    }
    flush();
    return html;
  };
  return source => {
    const html = fencedOnly ? renderBlocks(source) : parser.parse(source, { async: false });
    const fragment = purifier.sanitize(html, {
      RETURN_DOM_FRAGMENT: true,
      ALLOWED_TAGS: [
        "p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "em",
        "del", "blockquote", "pre", "code", "ul", "ol", "li", "hr", "a",
        "table", "thead", "tbody", "tr", "th", "td", "span"
      ],
      // Only our code renderer/highlighter emits classes; raw HTML is escaped.
      ALLOWED_ATTR: ["href", "title", "start", "class"],
      ALLOW_DATA_ATTR: false,
      ALLOW_ARIA_ATTR: false
    });
    for (const anchor of fragment.querySelectorAll("a")) {
      const href = anchor.getAttribute("href");
      if (href) {
        try {
          const protocol = new window.URL(href, window.location.href).protocol;
          if (!["http:", "https:", "mailto:"].includes(protocol)) {
            anchor.removeAttribute("href");
          }
        } catch {
          anchor.removeAttribute("href");
        }
      }
      anchor.setAttribute("target", "_blank");
      anchor.setAttribute("rel", "noopener noreferrer");
    }
    return fragment;
  };
}
