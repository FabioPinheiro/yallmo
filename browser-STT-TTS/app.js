import { OperationController, pollSequentially } from './bridge-client.js';
import { recordingAdapter, speechAdapter } from './browser-audio.js';
import { makeRequest } from './http-client.js';

const enable = document.querySelector('#enable');
const disable = document.querySelector('#disable');
const status = document.querySelector('#status');
const readiness = document.querySelector('#readiness');
const match = /^#token=([A-Za-z0-9_-]{43})$/.exec(location.hash);
const token = match?.[1];
// Keep credentials only in memory, including after setup; reload needs the REPL URL.
history.replaceState(null, '', location.pathname);
let current = null;
const show = (message, ready = false) => {
  status.textContent = message;
  readiness.textContent = ready ? 'Connected' : 'Not ready';
};

function stopTracks(stream) { stream?.getTracks().forEach(track => track.stop()); }
function invoke(action) {
  try { return Promise.resolve(action()); }
  catch (error) { return Promise.reject(error); }
}

function shutdown(session, message, notify = true) {
  if (!session?.active) return;
  session.active = false;
  session.controller?.close();
  session.abort.abort();
  session.test?.cancel();
  window.speechSynthesis?.cancel();
  stopTracks(session.stream);
  if (session.context) {
    session.context.onstatechange = null;
    void session.context.close().catch(() => {});
  }
  // Deliberately independent of the aborted session. No beacon/query credentials.
  // This also covers an uncertain connect response; reconnect uses a new client ID.
  if (notify && session.connectAttempted) {
    const request = makeRequest({ token, clientId: session.clientId });
    void request('/api/disconnect').catch(() => {});
  }
  if (current === session) {
    current = null;
    enable.disabled = !token;
    disable.disabled = true;
    show(message);
  }
}

function setupSpeech(session) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = ok => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      session.abort.signal.removeEventListener('abort', cancel);
      if (ok) resolve(); else reject(new Error('Speech activation failed'));
    };
    const cancel = () => finish(false);
    const timer = setTimeout(() => { session.test?.cancel(); finish(false); }, 4500);
    session.abort.signal.addEventListener('abort', cancel, { once: true });
    try {
      // Called directly in the Enable gesture, before awaiting microphone permission.
      // Completion is only a browser report, not verification of audible OS output.
      let started = false;
      session.test = speechAdapter(window.speechSynthesis, 'Audio enabled.', {
        onStarted: () => { started = true; },
        onComplete: () => finish(started),
        onError: () => finish(false)
      });
    } catch { finish(false); }
  });
}

function waitForSetup(promise, signal) {
  return new Promise((resolve, reject) => {
    const cancel = () => { cleanup(); reject(new Error('Setup cancelled')); };
    const cleanup = () => { clearTimeout(timer); signal.removeEventListener('abort', cancel); };
    const timer = setTimeout(() => { cleanup(); reject(new Error('Setup timed out')); }, 20000);
    signal.addEventListener('abort', cancel, { once: true });
    promise.then(value => { cleanup(); resolve(value); }, error => { cleanup(); reject(error); });
    if (signal.aborted) cancel();
  });
}

async function enableAudio() {
  if (current) shutdown(current, 'Starting a fresh audio connection.');
  const session = { active: true, abort: new AbortController(), clientId: crypto.randomUUID() };
  current = session;
  enable.disabled = true;
  disable.disabled = false;
  show('Enabling audio — accept microphone permission and listen for the speech test.');
  try {
    if (!navigator.mediaDevices?.getUserMedia || !window.AudioContext || !window.AudioWorkletNode ||
        !window.speechSynthesis || !window.SpeechSynthesisUtterance) throw new Error('Audio APIs unavailable');
    session.context = new AudioContext();
    // All three gesture-sensitive actions happen before the first await.
    const resumed = invoke(() => session.context.resume());
    const speech = setupSpeech(session);
    const permission = invoke(() => navigator.mediaDevices.getUserMedia({
      audio: { channelCount: { ideal: 1 }, echoCancellation: true }, video: false
    })).then(stream => {
      if (!session.active) { stopTracks(stream); throw new Error('Setup cancelled'); }
      session.stream = stream;
      return stream;
    });
    const module = invoke(() => session.context.audioWorklet.addModule('./capture-worklet.js'));
    await waitForSetup(Promise.all([resumed, speech, permission, module]), session.abort.signal);
    if (!session.active) return;
    if (session.context.state !== 'running' || !session.stream.getAudioTracks().length ||
        session.stream.getAudioTracks().some(track => track.readyState !== 'live')) throw new Error('Audio unavailable');
    session.test?.cancel();
    session.test = null;
    const lost = () => shutdown(session, 'Audio access was lost. Focus this tab and Enable audio again.');
    for (const track of session.stream.getTracks()) track.addEventListener('ended', lost, { once: true });
    session.context.onstatechange = () => { if (session.context.state !== 'running') lost(); };
    session.request = makeRequest({ token, clientId: session.clientId, signal: session.abort.signal });
    session.connectAttempted = true;
    const limits = await session.request('/api/connect', { clientId: session.clientId });
    if (!session.active) return;
    if (limits.heartbeatMs !== 1000 || limits.disconnectMs !== 5000 ||
        limits.maxRecordingMs !== 30000 || limits.maxUploadBytes !== 1048576) throw new Error('Unsupported bridge');
    session.controller = new OperationController({
      request: session.request,
      record: (maxMs, callbacks) => recordingAdapter(session.context, session.stream, maxMs, callbacks),
      speak: (text, callbacks) => speechAdapter(window.speechSynthesis, text, callbacks),
      onStatus: (message, ready = true) => { if (session.active) show(message, ready); },
      onFailure: () => shutdown(session, 'Audio connection failed. Focus this tab and Enable audio again.')
    });
    show('Connected — waiting for a REPL command.', true);
    enable.disabled = false;
    void pollSequentially(session.request, session.controller, session.abort.signal);
  } catch (error) {
    shutdown(session, error.status === 409 ?
      'Another audio tab is connected. Disable it or wait five seconds after closing it, then try again.' :
      'Audio setup failed or timed out. Check microphone permission and speech support, focus this tab, then Enable audio again.');
  }
}

enable.addEventListener('click', () => { void enableAudio(); });
disable.addEventListener('click', () => shutdown(current, 'Audio disabled. Microphone tracks and speech stopped.'));
window.addEventListener('pagehide', () => shutdown(current, 'Page left — Enable audio again after returning.'));
// A frozen page cannot maintain heartbeat or safely continue capture.
document.addEventListener('freeze', () => shutdown(current, 'Page frozen — Enable audio again.'));

if (!token || !crypto.randomUUID || location.protocol !== 'http:' || location.hostname !== '127.0.0.1' || location.search) {
  enable.disabled = true;
  show('Open the exact setup URL printed by the REPL (including its token fragment). Reloading this page requires that URL again.');
} else {
  show('Click Enable audio to grant microphone access and play a short speech test.');
}
