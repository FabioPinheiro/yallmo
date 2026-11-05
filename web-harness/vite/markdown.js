import createDOMPurify from "dompurify";
import { Marked } from "marked";

const escapeHtml = text => text.replace(/[&<>"']/g, character => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
})[character]);

// The returned fragment is the only HTML boundary. Raw HTML remains literal,
// embedded images never load, and sanitizer policy excludes controls, CSS, IDs,
// scripts, SVG and other active content.
export function createMarkdownRenderer(window) {
  const purifier = createDOMPurify(window);
  const parser = new Marked({
    gfm: true,
    renderer: {
      html: ({ text }) => escapeHtml(text),
      image: ({ text }) => escapeHtml(`[Image: ${text}]`),
      checkbox: ({ checked }) => checked ? "[x] " : "[ ] "
    }
  });
  return source => {
    const fragment = purifier.sanitize(parser.parse(source, { async: false }), {
      RETURN_DOM_FRAGMENT: true,
      ALLOWED_TAGS: [
        "p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "em",
        "del", "blockquote", "pre", "code", "ul", "ol", "li", "hr", "a",
        "table", "thead", "tbody", "tr", "th", "td"
      ],
      ALLOWED_ATTR: ["href", "title", "start"],
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
