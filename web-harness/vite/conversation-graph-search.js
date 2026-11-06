import { element } from "./conversation-graph-inspector.js";

// Query-only results: no permanent node inventory or pagination UI.
export class GraphSearch {
  constructor(document, find, open, liveChanged) {
    this.find = find;
    this.open = open;
    this.root = element(document, "div", "conversation-header graph-toolbar");
    const field = element(document, "div", "graph-search");
    this.input = element(document, "input");
    this.input.type = "search";
    this.input.placeholder = "ID, text or session";
    this.input.setAttribute("aria-label", "Search graph");
    this.results = element(document, "ul", "graph-search-results");
    this.results.setAttribute("aria-label", "Graph search results");
    this.results.hidden = true;
    const live = element(document, "label", "graph-live", "Live");
    this.live = element(document, "input");
    this.live.type = "checkbox";
    this.live.checked = true;
    this.live.addEventListener("change", () => liveChanged(this.live.checked));
    live.prepend(this.live);
    field.append(this.input, this.results);
    this.root.append(field, live);
    this.input.addEventListener("input", () => this.update());
  }

  update() {
    const query = this.input.value.trim();
    const matches = query ? this.find(query) : [];
    const shown = matches.slice(0, 30);
    const labels = shown.map(node => `${node.kind === "message" ? node.role : node.kind}: ${node.rawId}`);
    const signature = JSON.stringify([query, matches.length, shown.map(node => node.id), labels]);
    if (signature === this.signature) return;
    this.signature = signature;
    this.results.hidden = !query;
    this.results.replaceChildren();
    if (!query) return;
    shown.forEach((node, index) => {
      const row = element(this.input.ownerDocument, "li");
      const action = element(this.input.ownerDocument, "button", "", labels[index]);
      action.type = "button";
      action.addEventListener("click", () => {
        this.input.value = "";
        this.update();
        this.open(node.id);
      });
      row.append(action);
      this.results.append(row);
    });
    if (!matches.length || matches.length > shown.length) {
      this.results.append(element(this.input.ownerDocument, "li", "muted",
        matches.length ? `Showing 30 of ${matches.length}; refine your search.` : "No matches."));
    }
  }
}

// Native, locally retained view preference; it never narrows inventory requests.
export class GraphFilter {
  constructor(document, change) {
    this.root = element(document, "label", "graph-filter");
    this.root.append(element(document, "span", "", "Branches"));
    this.select = element(document, "select");
    for (const [value, label] of [["all", "All"], ["current", "Current"], ["siblings", "Current + siblings"]]) {
      const option = element(document, "option", "", label);
      option.value = value;
      this.select.append(option);
    }
    this.select.value = "siblings";
    this.select.addEventListener("change", change);
    this.root.append(this.select);
  }
}
