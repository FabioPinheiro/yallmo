import { recordingAdapter } from './browser-audio.js';
import { encodeWav, MAX_SAMPLES } from './audio-core.js';

export const MAX_RECORDING_MS = 30000;

export function available(env) {
  return !!(env.isSecureContext && env.navigator?.mediaDevices?.getUserMedia &&
    env.AudioContext && env.AudioWorkletNode);
}

// A single user-clicked capture using the shared browser DSP and capture adapter.
export function start(env, moduleUrl, callbacks, record = recordingAdapter) {
  let live = true;
  let context;
  let stream;
  let capture;
  let stopping = false;
  let started = false;
  let limit = false;
  const timers = new Set();
  const tracks = [];
  const later = (ms, action) => {
    const id = env.setTimeout(() => { timers.delete(id); if (live) action(); }, ms);
    timers.add(id);
    return id;
  };
  const clear = id => { env.clearTimeout(id); timers.delete(id); };
  const cleanup = () => {
    if (!live) return;
    live = false;
    for (const id of timers) env.clearTimeout(id);
    timers.clear();
    env.removeEventListener?.('pagehide', failed);
    env.document?.removeEventListener('freeze', failed);
    for (const track of tracks) {
      try { track.removeEventListener('ended', failed); track.stop(); } catch {}
    }
    try { capture?.cancel(); } catch {}
    if (context) {
      context.onstatechange = null;
      try { Promise.resolve(context.close()).catch(() => {}); } catch {}
    }
  };
  const failed = () => {
    if (!live) return;
    cleanup();
    callbacks.onError('Microphone capture failed or timed out. Check permission, HTTPS and device access, then try again.');
  };
  const stop = () => {
    if (!live || !capture || stopping) return;
    stopping = true;
    later(4500, failed);
    try { capture.stop(); } catch { failed(); }
  };
  try {
    if (!available(env)) throw new Error('Unsupported capture');
    env.addEventListener?.('pagehide', failed);
    env.document?.addEventListener('freeze', failed);
    context = new env.AudioContext();
    // Both are requested synchronously in the original button gesture, before any await.
    const resumed = context.resume();
    const permission = env.navigator.mediaDevices.getUserMedia({ audio: true }).then(value => {
      if (!live) { for (const track of value.getTracks()) { try { track.stop(); } catch {} } return; }
      stream = value;
      tracks.push(...value.getTracks());
      for (const track of tracks) track.addEventListener('ended', failed);
    });
    const setup = later(20000, failed);
    Promise.all([resumed, permission, context.audioWorklet.addModule(moduleUrl)]).then(() => {
      if (!live) return;
      if (!stream || context.state !== 'running' || stream.getAudioTracks().some(track => track.readyState !== 'live')) {
        failed(); return;
      }
      context.onstatechange = () => { if (live && context.state !== 'running') failed(); };
      capture = record(context, stream, MAX_RECORDING_MS, {
        onStarted() {
          if (!live || started) return;
          started = true;
          clear(setup);
          later(MAX_RECORDING_MS, () => { limit = true; stop(); });
          callbacks.onStarted();
        },
        onComplete(samples) {
          if (!live) return;
          let bytes;
          try { bytes = new Uint8Array(encodeWav(samples)); } catch { failed(); return; }
          const reachedLimit = limit || samples.length === MAX_SAMPLES;
          cleanup();
          callbacks.onComplete(bytes, reachedLimit);
        },
        onError: failed
      });
      // Accommodate adapters which complete synchronously during construction.
      if (!live) capture.cancel();
    }).catch(failed);
  } catch { failed(); }
  return { stop, cancel: cleanup };
}
