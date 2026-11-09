---
name: webgpu-specialist
description: Use for native browser WebGPU websites rendering simple 2D/3D models, including WGSL, canvas/device setup, vertex/index/uniform buffers, pipelines, cameras, animation, resize, device loss, validation, and accessible fallbacks.
disable-model-invocation: true
---

# WebGPU Specialist

Invoke when implementing, reviewing, or debugging native WebGPU rendering in a browser. Start from the project's existing ownership/lifecycle patterns; do not introduce a framework or model loader unnecessarily. These recipes use vanilla HTML/CSS/JavaScript and WGSL, not engine-specific APIs. Compute-heavy workloads, full glTF/PBR engines, and native bindings are outside this skill's focus.

## Workflow and index

1. Establish secure hosting, target browsers, fallback, canvas sizing, and device ownership: [Setup and canvas](references/setup-canvas.md).
2. Prove the render path with [Runnable host](recipes/browser-host.md) plus [Colored triangle](recipes/colored-triangle.md). Copy the host's `index.html` and `main.js`, then the triangle's `scene.js` into the same directory.
3. Match GPU byte layouts to WGSL and pipeline declarations: [WGSL, resources, pipelines](references/wgsl-resources-pipelines.md).
4. Replace only `scene.js` with [Indexed rotating cube](recipes/indexed-cube.md). Understand [Models, cameras, rendering](references/models-rendering.md) before importing assets.
5. Test resize, reduced motion, teardown, errors, loss, and unavailable devices using [Production and debugging](references/production-debugging.md).

Read only the sections needed. Every recipe names its dependencies and application integration points. Keep CPU-side source data for reconstruction; never reuse objects from a lost device. Do not cache canvas textures across frames or compile pipelines in the animation loop. Request optional features/limits only when needed and checked. Avoid unsupported browser/version promises.

## Source and verification boundary

Primary official reference: [MDN WebGPU API](https://developer.mozilla.org/en-US/docs/Web/API/WebGPU_API). This skill follows a supplied MDN-oriented research brief based on established API knowledge. **Live compatibility verification was unavailable during research; MDN pages and current browser/OS support tables were not fetched or verified.** Check live MDN compatibility information and run the examples on target browsers/GPUs before release. Static checks do not establish GPU execution or performance correctness.
