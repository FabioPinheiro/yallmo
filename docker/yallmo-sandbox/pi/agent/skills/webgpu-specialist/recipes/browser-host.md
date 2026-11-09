# Runnable browser host: setup, resize, animation, loss

[Skill index](../SKILL.md) · [Triangle scene](colored-triangle.md) · [Cube scene](indexed-cube.md)

Copy these two files into a new demo directory, then add **one** linked scene recipe as `scene.js`. No library, build step, shader loader, or missing matrix helper is required. Serve that directory, for example with `python3 -m http.server 8000`, and visit `http://localhost:8000` in a WebGPU-capable browser. Do not rely on opening a file URL. Check the console as well as the page status.

## `index.html`

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Native WebGPU model demo</title>
  <style>
    body { max-width: 60rem; margin: 2rem auto; padding: 0 1rem;
           font: 1rem/1.5 system-ui, sans-serif; }
    figure { margin: 0; }
    canvas { display: block; width: 100%; height: clamp(16rem, 55vh, 36rem);
             background: #101522; }
    button { padding: .5rem 1rem; margin: .5rem .5rem .5rem 0; }
    button:focus-visible { outline: 3px solid #0673dc; outline-offset: 3px; }
  </style>
</head>
<body>
  <h1>WebGPU model</h1>
  <figure>
    <canvas id="gpu" role="img" aria-label="Colored geometric model"
            aria-describedby="model-description">
      A colored geometric model; its description appears below.
    </canvas>
    <figcaption id="model-description">
      Demonstration: a colored triangle or a rotating colored cube.
      The model has no information beyond this description.
    </figcaption>
  </figure>
  <p id="fallback">If interactive graphics are unavailable, this description
    remains available. In a real viewer, include a static preview and model data.</p>
  <p id="status" role="status" aria-live="polite">Starting graphics…</p>
  <button id="pause" type="button" aria-pressed="false">Pause motion</button>
  <button id="retry" type="button">Restart graphics</button>
  <script type="module" src="./main.js"></script>
</body>
</html>
```

Canvas child fallback alone is insufficient when the browser supports canvas but not WebGPU. This page deliberately keeps an external description visible. Replace its generic copy/label with the actual model's meaning.

## `main.js`

```js
import { createScene } from "./scene.js";

const canvas = document.querySelector("#gpu");
const status = document.querySelector("#status");
const pauseButton = document.querySelector("#pause");
const retryButton = document.querySelector("#retry");
const motion = matchMedia("(prefers-reduced-motion: reduce)");
let paused = motion.matches;
let active = null;
let disposed = false;

function updatePauseButton() {
  pauseButton.textContent = paused ? "Resume motion" : "Pause motion";
  pauseButton.setAttribute("aria-pressed", String(paused));
}
function togglePause() { paused = !paused; updatePauseButton(); }
function motionChanged() { paused = motion.matches; updatePauseButton(); }
updatePauseButton();
pauseButton.addEventListener("click", togglePause);
motion.addEventListener("change", motionChanged);

function stop(s) {
  if (!s || s.stopped) return;
  s.stopped = true;
  cancelAnimationFrame(s.raf);
  s.scene?.destroy();
  s.context?.unconfigure();
  if (s.device) {
    s.device.removeEventListener("uncapturederror", s.onError);
    s.device.destroy(); // This host owns the device; omit for shared devices.
  }
  if (active === s) active = null;
}
function fail(s, message) {
  if (active !== s || s.stopped) return;
  console.error(message);
  stop(s);
  status.textContent = `${message} Description remains available; try Restart graphics.`;
}

async function checkedShader(device, code, label) {
  const module = device.createShaderModule({ code, label });
  const info = await module.getCompilationInfo();
  for (const m of info.messages) {
    console[m.type === "error" ? "error" : "warn"](
      `${label}:${m.lineNum}:${m.linePos}: ${m.message}`);
  }
  if (info.messages.some(m => m.type === "error")) {
    throw new Error(`Shader compilation failed: ${label}`);
  }
  return module;
}

// CSS in index.html has no canvas padding/border; rect is the content size.
function resize(s) {
  const rect = canvas.getBoundingClientRect();
  if (rect.width <= 0 || rect.height <= 0) return false;
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const w = Math.max(1, Math.round(rect.width * dpr));
  const h = Math.max(1, Math.round(rect.height * dpr));
  const max = s.device.limits.maxTextureDimension2D;
  const scale = Math.min(1, max / w, max / h, Math.sqrt(4_000_000 / (w * h)));
  const width = Math.max(1, Math.floor(w * scale));
  const height = Math.max(1, Math.floor(h * scale));
  if (width !== s.width || height !== s.height) {
    if (canvas.width !== width) canvas.width = width;
    if (canvas.height !== height) canvas.height = height;
    s.scene.resize(width, height);
    s.width = width;
    s.height = height;
  }
  return true;
}

async function start() {
  if (disposed) return;
  stop(active);
  const s = { stopped: false, raf: 0, width: 0, height: 0,
              device: null, context: null, scene: null, onError: null };
  active = s;
  status.textContent = "Starting graphics…";
  try {
    if (!globalThis.isSecureContext || !navigator.gpu) {
      throw new Error("WebGPU needs a secure context and a supported browser/device.");
    }
    const adapter = await navigator.gpu.requestAdapter();
    if (s.stopped) return;
    if (!adapter) throw new Error("No usable WebGPU adapter.");
    const device = await adapter.requestDevice();
    if (s.stopped) { device.destroy(); return; }
    s.device = device;
    s.onError = event => fail(s, `WebGPU error: ${event.error.message}`);
    device.addEventListener("uncapturederror", s.onError);
    device.lost.then(info => {
      // stop() marks this session first, so intentional teardown cannot retry.
      if (!s.stopped) fail(s, `Graphics device lost: ${info.message || info.reason}`);
    });
    const context = canvas.getContext("webgpu");
    if (!context) throw new Error("Cannot acquire a WebGPU canvas context.");
    s.context = context;
    const format = navigator.gpu.getPreferredCanvasFormat();
    context.configure({ device, format, alphaMode: "opaque" });

    device.pushErrorScope("validation");
    let scene;
    try {
      scene = await createScene({ device, format, checkedShader });
      s.scene = scene;
    } finally {
      const error = await device.popErrorScope();
      if (error) throw new Error(error.message);
    }
    if (s.stopped) { scene.destroy(); return; }
    status.textContent = "Graphics ready. Motion can be paused.";
    let previous;
    let time = 0;
    function frame(now) {
      if (s.stopped) return;
      const dt = previous === undefined ? 0 : Math.min((now - previous) / 1000, 0.05);
      previous = now;
      try {
        if (!document.hidden && resize(s)) {
          if (!paused) time += dt;
          // Scenes acquire a fresh canvas texture/view on every call.
          scene.render(context, time, s.width / s.height);
        }
      } catch (error) {
        fail(s, String(error));
        return;
      }
      s.raf = requestAnimationFrame(frame);
    }
    s.raf = requestAnimationFrame(frame);
  } catch (error) {
    // Also destroy a scene produced while asynchronous teardown was in flight.
    if (s.stopped) s.scene?.destroy();
    else fail(s, String(error));
  }
}

function pageHidden() { stop(active); }
function pageShown(event) { if (event.persisted) void start(); }
retryButton.addEventListener("click", start);
window.addEventListener("pagehide", pageHidden);
window.addEventListener("pageshow", pageShown);

// Application integration: call on component unmount. Pending init is invalidated.
export function dispose() {
  disposed = true;
  stop(active);
  pauseButton.removeEventListener("click", togglePause);
  retryButton.removeEventListener("click", start);
  motion.removeEventListener("change", motionChanged);
  window.removeEventListener("pagehide", pageHidden);
  window.removeEventListener("pageshow", pageShown);
}
void start();
```

`createScene({ device, format, checkedShader })` returns `{ resize(width, height), render(context, timeSeconds, aspect), destroy() }`. Scene teardown must be safe to repeat. Both supplied scenes await shader/pipeline creation before allocating mesh buffers. The host destroys its owned device on failed initialization, also retiring partially allocated resources. A shared-device adaptation must track and destroy partial scene allocations itself.

Restart is user-triggered and reconstructs **all** GPU resources, including pipelines/bind groups and depth. Stale async initialization never starts another loop. The frame loop clamps resume deltas and honors reduced motion; it still draws paused scenes to keep resize handling simple. For production static scenes, use dirty/on-demand drawing and explicit resize/DPR observation instead. Background frames do not submit work; returning from a long suspension advances by at most 50 ms.
