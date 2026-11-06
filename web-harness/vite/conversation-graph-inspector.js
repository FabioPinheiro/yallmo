export function element(document, tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

// One node-following inspector. Its body is mounted/unmounted by the safe Laminar callback.
export class GraphInspector {
  constructor(layer, position, inspect, focus, returnFocus) {
    this.layer = layer;
    this.position = position;
    this.inspect = inspect;
    this.focus = focus;
    this.returnFocus = returnFocus;
    this.document = layer.ownerDocument;
    this.window = this.document.defaultView;
    this.card = null;
    this.drag = null;
    this.hoverTimer = null;
    this.hoveredNode = null;
    // Dismiss before a node's later tap/click opens its replacement, never after that activation.
    this.onOutside = event => {
      // View-only controls preserve the inspector unless filtering actually hides its target.
      if (event.target.closest?.(".graph-filter, .graph-live")) return;
      if (this.card && !this.card.shell.contains(event.target)) this.close();
    };
    this.onKey = event => {
      if (event.key === "Escape" && this.card) {
        event.preventDefault();
        this.close();
        this.returnFocus();
      }
      else if (event.key === "Tab") this.recover();
    };
    this.onMove = event => {
      if (!this.drag || !this.card) return;
      this.card.dx += event.clientX - this.drag.x;
      this.card.dy += event.clientY - this.drag.y;
      this.drag = { x: event.clientX, y: event.clientY };
      this.updatePosition();
    };
    this.onUp = () => { this.drag = null; };
    this.document.addEventListener("pointerdown", this.onOutside, true);
    this.document.addEventListener("keydown", this.onKey);
    this.window.addEventListener("pointermove", this.onMove);
    this.window.addEventListener("pointerup", this.onUp);
    this.window.addEventListener("pointercancel", this.onUp);
  }

  preview(node) {
    if (this.card?.persistent) return;
    this.cancelHoverClose();
    this.open(node, true);
    this.hoveredNode = node.id;
  }

  leaveNode(id) {
    if (this.hoveredNode === id) this.hoveredNode = null;
    this.scheduleHoverClose();
  }

  cancelHoverClose() {
    this.window.clearTimeout(this.hoverTimer);
    this.hoverTimer = null;
  }

  scheduleHoverClose() {
    this.cancelHoverClose();
    if (!this.card || this.card.persistent) return;
    this.hoverTimer = this.window.setTimeout(() => {
      this.hoverTimer = null;
      if (this.card && !this.card.persistent && !this.hoveredNode && !this.card.hovered && !this.card.shell.contains(this.document.activeElement)) this.close();
    }, 120);
  }

  dismissPreview() {
    if (this.card && !this.card.persistent) this.close();
  }

  open(node, passive = false) {
    if (this.card?.id !== node.id) {
      this.close();
      const shell = element(this.document, "section", "graph-inspector");
      shell.tabIndex = -1;
      shell.setAttribute("aria-label", `Inspector ${node.rawId}`);
      const drag = element(this.document, "header", "graph-drag-handle");
      const body = element(this.document, "div", "graph-inspector-body");
      shell.append(drag, body);
      this.card = { id: node.id, shell, body, dx: 24, dy: 24, dispose: () => {}, persistent: !passive, hovered: false };
      shell.addEventListener("pointerenter", () => { this.hoveredNode = null; this.card.hovered = true; this.cancelHoverClose(); });
      shell.addEventListener("pointerleave", () => { this.card.hovered = false; this.scheduleHoverClose(); });
      shell.addEventListener("focusin", () => this.cancelHoverClose());
      shell.addEventListener("focusout", () => this.scheduleHoverClose());
      drag.addEventListener("pointerdown", event => {
        if (event.button !== 0) return;
        event.preventDefault();
        this.drag = { x: event.clientX, y: event.clientY };
      });
      this.layer.append(shell);
    }
    this.cancelHoverClose();
    this.card.persistent ||= !passive;
    this.update(node);
    this.updatePosition();
    this.recover();
    if (!passive) {
      this.card.shell.focus({ preventScroll: true });
      this.layer.parentElement?.scrollIntoView?.({ block: "nearest" });
    }
  }

  recover() {
    const card = this.card, width = this.layer.clientWidth, height = this.layer.clientHeight;
    if (!card || !width || !height) return;
    const anchor = this.position(card.id);
    const x = anchor.x + card.dx, y = anchor.y + card.dy;
    const cardWidth = card.shell.offsetWidth || Math.min(440, width - 16);
    const cardHeight = Math.min(Math.max(180, card.shell.offsetHeight), height - 16);
    card.dx += Math.max(8, Math.min(x, width - cardWidth - 8)) - x;
    card.dy += Math.max(8, Math.min(y, height - cardHeight - 8)) - y;
    this.updatePosition();
  }

  update(node) {
    const card = this.card;
    if (!card || card.id !== node.id) return;
    const signature = JSON.stringify(node);
    if (signature === card.signature) return;
    card.signature = signature;
    card.dispose();
    card.dispose = () => {};
    card.body.replaceChildren();
    if (node.kind === "message") card.dispose = this.inspect(node.rawId, card.body);
    else if (node.kind === "missing") {
      card.body.append(element(this.document, "h3", "", "Missing referenced message"), element(this.document, "code", "", node.rawId),
        element(this.document, "p", "", "Referenced artifact is unavailable; its position is reserved."));
    } else {
      card.body.append(element(this.document, "h3", "", `Session: ${node.rawId}`),
        element(this.document, "p", "", `Current published head: ${node.head ?? (node.error ? "unknown" : "(empty)")}`));
      if (node.error) card.body.append(element(this.document, "p", "error", `Snapshot unavailable; last known pointer shown if available. ${node.error}`));
      const focus = element(this.document, "button", "", "Highlight history / focus");
      focus.type = "button";
      focus.addEventListener("click", () => this.focus(node.rawId));
      card.body.append(focus);
    }
  }

  updatePosition() {
    const card = this.card;
    if (!card) return;
    const anchor = this.position(card.id), y = anchor.y + card.dy;
    card.shell.style.left = `${anchor.x + card.dx}px`;
    card.shell.style.top = `${y}px`;
    if (this.layer.clientHeight) card.shell.style.maxHeight = `${Math.max(80, this.layer.clientHeight - Math.max(0, y) - 8)}px`;
  }

  close() {
    this.cancelHoverClose();
    this.hoveredNode = null;
    this.drag = null;
    if (!this.card) return;
    this.card.dispose();
    this.card.shell.remove();
    this.card = null;
  }

  destroy() {
    this.document.removeEventListener("pointerdown", this.onOutside, true);
    this.document.removeEventListener("keydown", this.onKey);
    this.window.removeEventListener("pointermove", this.onMove);
    this.window.removeEventListener("pointerup", this.onUp);
    this.window.removeEventListener("pointercancel", this.onUp);
    this.close();
  }
}
