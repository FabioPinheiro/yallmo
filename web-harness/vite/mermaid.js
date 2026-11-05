import createDOMPurify from "dompurify";

let nextDiagramId = 0;

// Lazy-load the bundled library only when a shared Markdown body contains Mermaid.
// Configuration belongs to the application, never to stored diagram content.
export function createMermaidEnhancer(window, loadMermaid = () => import("mermaid")) {
  const purifier = createDOMPurify(window);
  let library;
  const getLibrary = () => library ??= loadMermaid().then(({ default: mermaid }) => {
    // SVG images cannot inherit the page's CSS variables, so resolve the shared
    // palette into Mermaid's generated styles before crossing the image boundary.
    const css = window.getComputedStyle(window.document.documentElement);
    const color = (name, fallback) => css.getPropertyValue(name).trim() || fallback;
    const background = color("--background", "#10151d");
    const surface = color("--surface", "#1b2431");
    const selected = color("--surface-selected", "#243d5e");
    const text = color("--text", "#e2e8f0");
    const accent = color("--accent", "#78b3ff");
    const border = color("--control-border", "#61738c");
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: "strict",
      suppressErrorRendering: true,
      htmlLabels: false,
      flowchart: { htmlLabels: false },
      maxTextSize: 50000,
      maxEdges: 500,
      theme: "base",
      themeVariables: {
        darkMode: true,
        background,
        fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif",
        fontSize: "14px",
        primaryColor: selected,
        primaryTextColor: text,
        primaryBorderColor: accent,
        secondaryColor: color("--surface-hover", "#263345"),
        secondaryTextColor: text,
        secondaryBorderColor: border,
        tertiaryColor: color("--surface-info", "#182b42"),
        tertiaryTextColor: text,
        tertiaryBorderColor: border,
        mainBkg: selected,
        textColor: text,
        lineColor: color("--text-muted", "#a3b1c4"),
        edgeLabelBackground: background,
        clusterBkg: surface,
        clusterBorder: border,
        titleColor: text,
        actorBkg: selected,
        actorBorder: accent,
        actorTextColor: text,
        actorLineColor: border,
        signalColor: text,
        signalTextColor: text,
        labelBoxBkgColor: surface,
        labelBoxBorderColor: border,
        labelTextColor: text,
        noteBkgColor: surface,
        noteBorderColor: border,
        noteTextColor: text,
        activationBkgColor: selected,
        activationBorderColor: accent
      }
    });
    return mermaid;
  });

  // The caller owns the host and cancels on unmount. In-flight rendering can
  // finish, but must never update an old host or start its remaining diagrams.
  return host => {
    let cancelled = false;
    const codes = [...host.querySelectorAll("pre > code.language-mermaid")];
    const active = code => !cancelled && host.isConnected && host.contains(code);
    const enhance = async () => {
      for (const code of codes) {
        if (!active(code)) return;
        const source = code.textContent;
        let scratch;
        try {
          // Keep application configuration fixed. Also exclude resource-bearing
          // features before rendering: even a temporary SVG must not load remote
          // images/fonts through image nodes, raw HTML or custom CSS. Backslash
          // escapes could otherwise conceal CSS keywords/URLs from these guards.
          if (source.length > 50000 || /^\s*---/.test(source) || /%%\s*\{/.test(source)
            || /\\|<[a-z!\/]|\b(?:classDef|linkStyle|style)\b|\b(?:img|icon)["']?\s*:/i.test(source)) {
            throw new Error("Unsupported diagram configuration, resources or size");
          }
          const mermaid = await getLibrary();
          if (!active(code)) return;
          scratch = window.document.createElement("div");
          scratch.style.cssText = "position:absolute;left:-100000px;top:0;pointer-events:none";
          scratch.setAttribute("aria-hidden", "true");
          window.document.body.appendChild(scratch);
          const { svg } = await mermaid.render(`sdlc-mermaid-${++nextDiagramId}`, source, scratch);
          if (!active(code)) return;
          const fragment = purifier.sanitize(svg, {
            RETURN_DOM_FRAGMENT: true,
            USE_PROFILES: { svg: true, svgFilters: true },
            FORBID_TAGS: ["foreignObject", "script", "image", "a"]
          });
          const root = fragment.querySelector("svg");
          if (!root) throw new Error("Missing diagram SVG");
          // Mermaid emits width="100%" with only a viewBox/max-width. As an
          // external image that has no reliable intrinsic size and can inflate to
          // the entire card. Give it real dimensions; CSS may shrink, never zoom.
          const viewBox = root.getAttribute("viewBox")?.trim().split(/[\s,]+/).map(Number);
          if (viewBox?.length === 4 && viewBox.every(Number.isFinite) && viewBox[2] > 0 && viewBox[3] > 0) {
            root.setAttribute("width", String(viewBox[2]));
            root.setAttribute("height", String(viewBox[3]));
            for (const property of ["width", "height", "max-width", "max-height"]) root.style.removeProperty(property);
          }
          const safeSvg = new window.XMLSerializer().serializeToString(root);
          const figure = window.document.createElement("figure");
          figure.className = "sdlc-mermaid";
          const image = window.document.createElement("img");
          image.alt = "Mermaid diagram; text available in Diagram source";
          // SVG-as-image is an inert boundary: no callbacks, navigation, scripts
          // or external resources. Never insert generated SVG into the app DOM.
          image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(safeSvg)}`;
          const expand = window.document.createElement("button");
          expand.type = "button";
          expand.className = "sdlc-mermaid-expand";
          expand.textContent = "Expand diagram";
          expand.setAttribute("aria-expanded", "false");
          expand.onclick = () => {
            if (cancelled || !host.contains(figure)) return;
            const expanded = figure.classList.toggle("is-expanded");
            expand.setAttribute("aria-expanded", String(expanded));
            expand.textContent = expanded ? "Fit diagram" : "Expand diagram";
          };
          const details = window.document.createElement("details");
          const summary = window.document.createElement("summary");
          summary.textContent = "Diagram source";
          details.appendChild(summary);
          const pre = code.parentElement;
          pre.replaceWith(figure);
          details.appendChild(pre);
          figure.append(image, expand, details);
          image.onerror = () => {
            if (cancelled || !host.contains(figure)) return;
            image.remove();
            expand.remove();
            details.open = true;
            summary.textContent = "Diagram unavailable — source";
          };
        } catch {
          if (!active(code)) return;
          const message = window.document.createElement("p");
          message.className = "sdlc-mermaid-error";
          message.setAttribute("role", "status");
          message.textContent = "Diagram unavailable — Mermaid source shown below.";
          code.parentElement.before(message);
        } finally {
          scratch?.remove();
        }
      }
    };
    void enhance();
    return () => { cancelled = true; };
  };
}
