# Setup and canvas

[Skill index](../SKILL.md) · [Runnable host](../recipes/browser-host.md)

## Availability and ownership

Serve over HTTPS or a trustworthy local development origin such as `http://localhost`. Test `globalThis.isSecureContext && navigator.gpu`, then handle a null adapter, rejected `requestDevice()`, or missing `canvas.getContext("webgpu")`. API presence alone does not mean usable hardware. Do not user-agent sniff. A `powerPreference` is a hint, not a guarantee; omit it unless justified.

The host recipe owns one device and one canvas context. In an application, mount it only after the canvas exists and invoke teardown on unmount. If devices are shared, destroy only the renderer's resources, not the shared device. A canvas cannot switch to a different context type after obtaining a WebGPU context: use a separate/replaced canvas for a Canvas2D or WebGL fallback.

Request the portable baseline via `adapter.requestDevice()` initially. Check `adapter.features.has(name)` and adapter limits before supplying `requiredFeatures`/`requiredLimits`; afterward obey `device.features` and `device.limits`. Optional features are not enabled just because the adapter advertises them. Start loss/error monitoring immediately after acquisition.

Use `navigator.gpu.getPreferredCanvasFormat()` and `context.configure({ device, format, alphaMode: "opaque" })`. Transparent compositing needs deliberate `alphaMode: "premultiplied"`, appropriate clear alpha, and consistent shader/blend alpha conventions. No explicit present operation is required.

## CSS versus backing size

CSS determines layout; `canvas.width`/`height` determine render resolution. Keep CSS sizing independent of the intrinsic canvas dimensions to avoid layout feedback. Read its content size (the recipe has no border/padding), multiply by an effective DPR, round, and cap both dimensions uniformly to:

- `device.limits.maxTextureDimension2D`;
- a deliberate pixel budget (e.g. four million pixels, not an API limit).

Skip drawing zero-size/hidden canvases. A `ResizeObserver` can mark size dirty; measuring before frames also detects DPR changes when moving windows between monitors. For on-demand rendering, observe size and explicitly schedule a redraw on relevant DPR/layout changes.

On backing-size changes, assign only changed dimensions, recreate depth/MSAA/intermediate attachments at matching size, and update camera aspect. Configuration normally survives resize: do not call `configure()` each frame. Get a new `context.getCurrentTexture().createView()` inside every frame. Canvas textures are browser-owned; do not destroy them.

The host caps DPR at 2 as an application quality/performance choice; tune it rather than treating it as a standard. It samples layout each active frame for simplicity. Large apps may batch measurements or use observers.

## Fallback and integration points

Always keep equivalent model information in semantic HTML: caption, summary, dimensions or data table, and any actions. Fallback may be a static image/SVG or a separately implemented Canvas2D/WebGL view. Do not silently promise WebGL fallback without implementing it. Keep critical site navigation functional when GPU setup fails. The host deliberately uses a visible textual fallback and user-triggered restart, not automatic retry loops.

Application-specific seams: DOM IDs, status copy, static preview, desired pixel budget, shared-device policy, routing/mount hooks, and scene selection. Keep these outside shader/resource code. A real model viewer should expose keyboard-accessible camera/reset controls with labels and document interaction; the demonstration cube has only a pause control.

Reference: [MDN GPUCanvasContext](https://developer.mozilla.org/en-US/docs/Web/API/GPUCanvasContext), [GPU requestAdapter](https://developer.mozilla.org/en-US/docs/Web/API/GPU/requestAdapter).
