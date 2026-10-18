import { encodeWav, OUTPUT_RATE } from './audio-core.js';

export function validateSnapshot(snapshot) {
  if (!snapshot || snapshot.heartbeatMs !== 1000) throw new Error('Invalid bridge response');
  const op = snapshot.operation;
  if (op === undefined) return undefined;
  if (!op || typeof op.id !== 'string' || !/^[a-f0-9-]{36}$/i.test(op.id)) throw new Error('Invalid operation');
  if (op.kind === 'record') {
    if (!['start', 'recording', 'stop'].includes(op.phase) ||
        !Number.isInteger(op.maxRecordingMs) || op.maxRecordingMs < 1000 || op.maxRecordingMs > 30000 ||
        op.text !== undefined) throw new Error('Invalid recording');
  } else if (op.kind === 'speak') {
    if (!['start', 'speaking'].includes(op.phase) || op.maxRecordingMs !== 0 ||
        typeof op.text !== 'string' || !op.text.trim() || op.text.length > 16000) throw new Error('Invalid speech');
  } else throw new Error('Invalid operation kind');
  return op;
}

// One controller per Enable/connect. Asynchronous callbacks carry the local operation
// identity, not just a server ID, so a discarded start can never publish audio later.
export class OperationController {
  constructor({ request, record, speak, onStatus, onFailure, timers = globalThis }) {
    Object.assign(this, { request, record, speak, onStatus, onFailure, timers });
    this.active = true;
    this.current = null;
    this.lastObservedId = null;
  }

  isCurrent(op) { return this.active && this.current === op && !op.failed && !op.done; }

  clearTimer(op) {
    if (op.timer !== undefined) this.timers.clearTimeout(op.timer);
    op.timer = undefined;
  }

  arm(op, ms) {
    this.clearTimer(op);
    op.timer = this.timers.setTimeout(() => this.operationError(op), ms);
  }

  discard() {
    const op = this.current;
    this.current = null;
    if (!op) return;
    this.clearTimer(op);
    op.abort.abort();
    op.payload = null;
    op.handle?.cancel();
    op.handle = null;
  }

  close() { this.active = false; this.discard(); }

  fail() {
    if (!this.active) return;
    this.close();
    this.onFailure();
  }

  requestFailed(op, error) {
    if (!this.active || this.current !== op) return;
    // Operation endpoints use 409 for stale IDs/transitions. Esc or a new `t`
    // can invalidate a request before the next poll observes the change.
    // Discard without retrying bytes/events; the next poll remains authoritative.
    if (error?.status === 409) {
      this.discard();
      this.onStatus('Connected — waiting for a REPL command.');
    } else this.fail();
  }

  reconcile(snapshot) {
    if (!this.active) return;
    const desired = validateSnapshot(snapshot);
    if (this.current?.failed) return;
    if (!desired || this.current?.id !== desired.id) this.discard();
    if (!desired) {
      this.onStatus('Connected — waiting for a REPL command.');
      return;
    }
    if (this.current) {
      if (desired.kind !== this.current.kind) throw new Error('Operation changed kind');
      if (desired.phase === 'stop' && !this.current.done) this.current.handle?.stop();
      return;
    }
    // Polls are strictly sequential. Remember the last observed ID across idle
    // snapshots; never replay it or invent a recorder for an already-started phase.
    if (desired.id === this.lastObservedId) return;
    this.lastObservedId = desired.id;
    if (desired.phase !== 'start') return;
    const op = { ...desired, abort: new AbortController(), acked: false, actualStarted: false,
      done: false, failed: false, final: false, sending: false, payload: null, handle: null };
    this.current = op;
    this.arm(op, 4500);
    const callbacks = {
      onStarted: () => this.started(op),
      onComplete: payload => this.completed(op, payload),
      onError: () => this.operationError(op)
    };
    try {
      const handle = op.kind === 'record' ? this.record(op.maxRecordingMs, callbacks) : this.speak(op.text, callbacks);
      if (this.isCurrent(op)) op.handle = handle;
      else handle.cancel();
    } catch { void this.operationError(op); }
  }

  async started(op) {
    if (!this.isCurrent(op) || op.actualStarted) return;
    op.actualStarted = true;
    this.arm(op, op.kind === 'speak' ? 120000 : op.maxRecordingMs + 4500);
    try {
      await this.request('/api/event', { operationId: op.id, event: 'started' }, op.abort.signal);
      if (!this.isCurrent(op)) return;
      op.acked = true;
      this.onStatus(op.kind === 'record' ? 'Recording — Space stops; Esc discards in the REPL.' : 'Speaking — browser reported speech start.');
      await this.sendCompletion(op);
    } catch (error) { this.requestFailed(op, error); }
  }

  completed(op, payload) {
    if (!this.isCurrent(op) || op.final) return;
    if (!op.actualStarted || (op.kind === 'record' && (!(payload instanceof Int16Array) ||
        payload.length < 1 || payload.length > Math.floor(op.maxRecordingMs * OUTPUT_RATE / 1000)))) {
      void this.operationError(op);
      return;
    }
    op.final = true;
    op.payload = payload;
    void this.sendCompletion(op);
  }

  async sendCompletion(op) {
    if (!this.isCurrent(op) || !op.acked || !op.final || op.sending) return;
    op.sending = true;
    this.arm(op, 4500);
    try {
      if (op.kind === 'record') {
        const wav = encodeWav(op.payload);
        op.payload = null;
        this.onStatus('Sending stopped recording to the REPL…');
        await this.request(`/api/audio/${op.id}`, wav, op.abort.signal);
      } else {
        await this.request('/api/event', { operationId: op.id, event: 'ended' }, op.abort.signal);
      }
      if (!this.isCurrent(op)) return;
      op.done = true;
      this.clearTimer(op);
      op.handle?.cancel();
      op.handle = null;
      this.onStatus('Connected — waiting for a REPL command.');
    } catch (error) { this.requestFailed(op, error); }
  }

  async operationError(op) {
    if (!this.isCurrent(op)) return;
    op.failed = true;
    this.clearTimer(op);
    op.payload = null;
    op.handle?.cancel();
    op.handle = null;
    this.onStatus('Audio failed — disabling. Enable audio again to recover.', false);
    try { await this.request('/api/event', { operationId: op.id, event: 'error' }, op.abort.signal); }
    catch (error) { this.requestFailed(op, error); return; }
    if (this.active && this.current === op) this.fail();
  }
}

export function delay(ms, signal) {
  return new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(new Error('Cancelled')); };
    const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve(); }, ms);
    signal.addEventListener('abort', abort, { once: true });
    if (signal.aborted) abort();
  });
}

export async function pollSequentially(request, controller, signal, wait = delay) {
  try {
    while (controller.active && !signal.aborted) {
      const snapshot = await request('/api/poll');
      if (!controller.active || signal.aborted) return;
      controller.reconcile(snapshot);
      await wait(1000, signal);
    }
  } catch { if (!signal.aborted) controller.fail(); }
}
