# Production, debugging, and release checks

[Skill index](../SKILL.md) · [Host lifecycle implementation](../recipes/browser-host.md)

## Validation and diagnostics

Label modules, buffers, textures, pipelines, and encoders. Inspect shader compilation messages and caught pipeline promise rejections. `uncapturederror` catches GPU errors not intercepted by scopes; many validation failures are asynchronous and will not throw at the offending JavaScript call. Error scopes do not replace `try/catch`.

To isolate a suspect synchronous resource creation or a single frame, use this pattern **instead of** running the same operation concurrently outside the scope:

```js
// device and suspectOperation are application-provided. For a frame, the
// operation must encode, finish, AND submit while the scope is active.
async function validateOperation(device, suspectOperation) {
  device.pushErrorScope("validation");
  let result;
  try {
    result = await suspectOperation();
  } finally {
    const error = await device.popErrorScope();
    if (error) throw new Error(`GPU validation: ${error.message}`);
  }
  return result;
}
```

Scopes are device-wide stacks, not lexical or per-renderer isolation: serialize diagnostic work, especially on shared devices. Add separate `"out-of-memory"` or `"internal"` scopes when useful and pop in reverse order. A production diagnostic wrapper may preserve both a JavaScript exception and a GPU scope error instead of allowing the latter to supersede the former. Do not add awaited diagnostic barriers to every production frame.

Debug in this order:

1. Clear-only pass: context configured, nonzero backing size, visible canvas, correct target format?
2. Vertex-index triangle: correct WGSL entry points, compilation diagnostics, `draw(3)`, ended pass, submitted commands?
3. Vertex/index buffers: declared usage, copied bytes/alignment, stride/offset/location, correct index format/count and range?
4. Transform: finite values, column-major layout, camera looking toward geometry, 0..1 depth projection, near/far clipping?
5. Depth: matching format/size/sample count, clear 1 with compare less, depth writes enabled?
6. Culling: disable temporarily; verify winding/negative scales. Check viewport/scissor if customized.
7. Bindings: correct group/binding indices, layout compatibility, resource size/type, dynamic offset alignment?
8. Textures/materials/import: correct sampling/color space, upload/copy layout, fetch/CORS, asset transforms?

Encoder/pass debug groups and markers can help browser GPU captures. Browser-specific tooling must be verified on the target browser. JavaScript queue submission duration is not GPU render time; use supported and explicitly requested timestamp-query features for GPU measurements. Avoid synchronous readbacks and per-frame queue completion waits.

## Lifecycle, loss, and cleanup

`device.lost` resolves once; stop animation/uploads immediately, invalidate pending async work, and report a fallback. Reacquire adapter/device, reconfigure the context, and recreate every shader, pipeline, buffer, texture, and bind group on retry. Old objects belong to the old device. Keep CPU assets/descriptors. Use bounded retries or a user button, never unconditional loss/restart recursion. Intentional destruction must not restart rendering.

The host uses a stopped session token to reject stale initialization, explicit user restart, `pagehide` shutdown, and `pageshow` restoration from the back-forward cache. `dispose()` removes listeners and stops permanently for component unmount. Framework integration must actually call it; exporting the function is not itself lifecycle registration.

Stop future use before destroying resources. Destroy buffers and textures only after any encoded references have been submitted; already submitted work is handled by the implementation. Release pipeline/bind-group/module references (they have no `destroy()`). Do not destroy browser-owned current canvas textures. Cancel RAF, disconnect any added observers, remove listeners, unconfigure the owned context, and destroy a device only when exclusively owned. A shared-device renderer needs its own partial-allocation cleanup on initialization failure.

## Progressive delivery and performance

- Retain static model meaning and critical actions outside the canvas. Supply image/SVG or separately implemented alternative rendering where product requirements demand it. Handle unsupported API, null adapter, rejected device, failed shader, and lost device explicitly.
- Respect reduced motion, offer a keyboard-operable pause, and avoid essential information conveyed only by animation/color. Label the model and expose data semantically. Do not announce per-frame updates to assistive technology.
- Request only required features/limits. Bound texture sizes, vertex counts, allocation totals, DPR/pixel count, and untrusted workloads. External assets still follow normal fetch/CORS rules.
- Reuse pipelines/buffers/bind groups; batch compatible draws, instance repeated meshes, and update only changing data. Consider asynchronous pipeline creation, static/on-demand rendering, and page visibility. Optimize from measured target-device behavior, not promises about WebGPU being automatically faster.
- Confirm color-space handling when adding textures/output conversion; do not blindly gamma-encode twice or assume vertex colors are an sRGB image material.

## Manual release matrix (not claimed executed)

- Serve via localhost/HTTPS; render the triangle, then the cube, with no console/validation errors.
- Exercise absent `navigator.gpu`, null adapter, device rejection, and deliberate bad WGSL/bindings via a test harness; verify understandable fallback and no running submission loop. Never mutate browser globals in production code to simulate support.
- Resize container and window; change DPR; test zero-size → visible, portrait, huge dimensions, and high-DPI. Verify finite matrices, matching depth size, bounded resolution, and no resize-triggered pipeline recreation.
- Pause/resume, toggle OS reduced motion, background/resume the tab, keyboard-navigate controls, and inspect accessible labels/status. Ensure equivalent model information remains available without GPU access.
- Repeatedly restart and dispose during each awaited initialization step; verify only one live loop, no stale scene becoming active, and no restart after disposal. Test back/forward-cache restore.
- In a development-only harness call `device.destroy()` while active to exercise the loss handler, then click Restart. Separately call normal teardown and ensure it does not recover. This tests intentional loss paths, **not** real driver resets: test actual loss separately where feasible.
- Run representative assets on actual target browser/OS/GPU combinations. Record feature/limit failures and frame/memory measurements. Mobile power/thermal behavior and graphics policy differ from desktop.

Primary source: [MDN WebGPU API](https://developer.mozilla.org/en-US/docs/Web/API/WebGPU_API); method reference: [GPUDevice.pushErrorScope](https://developer.mozilla.org/en-US/docs/Web/API/GPUDevice/pushErrorScope). **Live compatibility verification was unavailable during research.** No browser-version support or measured performance claim is implied by these recipes.
