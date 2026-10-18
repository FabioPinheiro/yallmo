import test from 'node:test';
import assert from 'node:assert/strict';
import { OperationController, validateSnapshot, pollSequentially } from './bridge-client.js';

const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const record = (n = 1, phase = 'start') => ({ id: id(n), kind: 'record', phase, maxRecordingMs: 30000 });
const speech = (n = 1, phase = 'start') => ({ id: id(n), kind: 'speak', phase, maxRecordingMs: 0, text: 'Plain <assistant> text' });
const snapshot = operation => operation ? { heartbeatMs: 1000, operation } : { heartbeatMs: 1000 };
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const tick = () => new Promise(resolve => setImmediate(resolve));

function harness(respond = async () => ({})) {
  const calls = [], starts = [], states = [], timers = new Map();
  let failures = 0, timerId = 0;
  const start = (kind, argument, callbacks) => {
    const local = { kind, argument, callbacks, cancelled: 0, stopped: 0 };
    starts.push(local);
    return { cancel: () => local.cancelled++, stop: () => local.stopped++ };
  };
  const controller = new OperationController({
    request: async (path, body, signal) => { calls.push({ path, body, signal }); return respond(path, body, signal); },
    record: (ms, callbacks) => start('record', ms, callbacks),
    speak: (text, callbacks) => start('speak', text, callbacks),
    onStatus: state => states.push(state), onFailure: () => failures++,
    timers: { setTimeout: callback => { timers.set(++timerId, callback); return timerId; }, clearTimeout: id => timers.delete(id) }
  });
  return { controller, calls, starts, states, timers, failures: () => failures,
    see: operation => controller.reconcile(snapshot(operation)) };
}

test('snapshots validated; null operation is not mistaken for omitted idle', () => {
  assert.equal(validateSnapshot(snapshot()), undefined);
  for (const invalid of [{ heartbeatMs: 1000, operation: null }, snapshot({ ...record(), maxRecordingMs: 30001 }),
    snapshot({ ...record(), text: 'unexpected' }), snapshot({ ...speech(), text: ' '.repeat(5) }),
    snapshot({ ...speech(), text: 'x'.repeat(16001) }), snapshot({ ...record(), id: '../escape' })]) {
    assert.throws(() => validateSnapshot(invalid));
  }
});

test('idle is silent; repeated starts deduplicate; readiness requires actual capture and acknowledged start', async () => {
  const h = harness();
  h.see();
  assert.equal(h.starts.length, 0);
  h.see(record()); h.see(record());
  assert.equal(h.starts.length, 1);
  assert.equal(h.calls.length, 0);
  await h.starts[0].callbacks.onStarted();
  await h.starts[0].callbacks.onStarted();
  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].body.event, 'started');
  h.see(record(1, 'stop'));
  assert.equal(h.starts[0].stopped, 1);
  h.starts[0].callbacks.onComplete(new Int16Array([10, 20]));
  await tick();
  assert.equal(h.calls[1].path, `/api/audio/${id(1)}`);
  assert.equal(h.calls[1].body.byteLength, 48);
  assert.equal(h.controller.current.done, true);
  h.see(record()); h.see(); h.see(record());
  assert.equal(h.starts.length, 1);
  assert.equal(h.timers.size, 0);
  h.controller.close();
});

test('final upload waits for successful started acknowledgement', async () => {
  const ack = deferred();
  const h = harness((path, body) => body?.event === 'started' ? ack.promise : {});
  h.see(record());
  const started = h.starts[0].callbacks.onStarted();
  h.starts[0].callbacks.onComplete(new Int16Array([42]));
  await tick();
  assert.equal(h.calls.length, 1);
  ack.resolve({}); await started; await tick();
  assert.equal(h.calls.length, 2);
  assert.equal(h.calls[1].path, `/api/audio/${id(1)}`);
  h.controller.close();
});

test('cancelled late start and late acknowledgement cannot upload or restart', async () => {
  const ack = deferred();
  const h = harness(() => ack.promise);
  h.see(record());
  const old = h.starts[0];
  const started = old.callbacks.onStarted();
  h.see();
  assert.equal(old.cancelled, 1);
  assert.equal(h.calls[0].signal.aborted, true);
  old.callbacks.onComplete(new Int16Array([42]));
  ack.resolve({}); await started;
  await old.callbacks.onStarted();
  h.see(record());
  assert.equal(h.starts.length, 1);
  assert.equal(h.calls.length, 1);
  h.controller.close();
});

test('authoritative replacement discards prior operation and callbacks; missing local started phases do not restart', async () => {
  const h = harness();
  h.see(record());
  const old = h.starts[0];
  h.see(speech(2));
  assert.equal(old.cancelled, 1);
  await old.callbacks.onStarted();
  old.callbacks.onComplete(new Int16Array([42]));
  assert.equal(h.calls.length, 0);
  assert.equal(h.starts[1].kind, 'speak');
  h.see(record(3, 'recording')); h.see(record(4, 'stop')); h.see(speech(5, 'speaking'));
  assert.equal(h.starts.length, 2);
  h.controller.close();
});

test('in-flight upload is aborted on cancellation; no retained PCM or restarted operation', async () => {
  const upload = deferred();
  const h = harness(path => path.startsWith('/api/audio/') ? upload.promise : {});
  h.see(record());
  await h.starts[0].callbacks.onStarted();
  h.starts[0].callbacks.onComplete(new Int16Array([42]));
  await tick();
  const op = h.controller.current;
  assert.equal(op.payload, null);
  h.see();
  assert.equal(h.calls[1].signal.aborted, true);
  upload.reject(new Error('aborted')); await tick();
  assert.equal(h.failures(), 0);
  h.see(record());
  assert.equal(h.starts.length, 1);
  h.controller.close();
});

test('uncertain started acknowledgement disables and discards without upload/replay', async () => {
  const h = harness(async () => { throw new Error('lost response'); });
  h.see(record());
  await h.starts[0].callbacks.onStarted();
  h.starts[0].callbacks.onComplete(new Int16Array([42]));
  h.see(record());
  assert.equal(h.failures(), 1);
  assert.equal(h.controller.active, false);
  assert.equal(h.starts[0].cancelled, 1);
  assert.equal(h.calls.length, 1);
});

test('stale start acknowledgement before the next poll discards without disconnecting or replaying', async () => {
  for (const operation of [record, speech]) {
    const ack = deferred();
    const h = harness((path, body) => body?.operationId === id(1) ? ack.promise : {});
    h.see(operation());
    const old = h.starts[0];
    const started = old.callbacks.onStarted();
    old.callbacks.onComplete(operation === record ? new Int16Array([42]) : undefined);
    // Backend has cancelled/replaced A, but the page has not observed idle/B yet.
    ack.reject(Object.assign(new Error('stale'), { status: 409 }));
    await started; await tick();
    assert.equal(h.controller.active, true);
    assert.equal(h.failures(), 0);
    assert.equal(h.controller.current, null);
    assert.equal(old.cancelled, 1);
    assert.equal(h.calls[0].signal.aborted, true);
    assert.equal(h.timers.size, 0);
    h.see(operation()); // Even an old in-flight snapshot must not replay A.
    h.see();
    await old.callbacks.onStarted();
    old.callbacks.onComplete(operation === record ? new Int16Array([42]) : undefined);
    assert.equal(h.starts.length, 1);
    assert.equal(h.calls.length, 1);
    h.see(speech(2));
    await h.starts[1].callbacks.onStarted();
    assert.equal(h.starts.length, 2);
    assert.equal(h.calls[1].body.operationId, id(2));
    assert.equal(h.controller.current.acked, true);
    h.controller.close();
  }
});

test('stale upload or speech end before cancellation/replacement poll keeps connection ready', async () => {
  for (const operation of [record, speech]) {
    const final = deferred();
    const h = harness((path, body) => body?.event === 'started' ? {} : final.promise);
    h.see(operation());
    const old = h.starts[0];
    await old.callbacks.onStarted();
    old.callbacks.onComplete(operation === record ? new Int16Array([42]) : undefined);
    await tick();
    const op = h.controller.current;
    assert.equal(h.calls.length, 2);
    final.reject(Object.assign(new Error('stale'), { status: 409 }));
    await tick();
    assert.equal(h.controller.active, true);
    assert.equal(h.failures(), 0);
    assert.equal(h.controller.current, null);
    assert.equal(op.payload, null);
    assert.equal(op.abort.signal.aborted, true);
    assert.equal(old.cancelled, 1);
    assert.equal(h.timers.size, 0);
    h.see(operation()); h.see();
    old.callbacks.onComplete(operation === record ? new Int16Array([42]) : undefined);
    assert.equal(h.calls.length, 2);
    assert.equal(h.starts.length, 1);
    h.see(record(2));
    assert.equal(h.starts.length, 2);
    h.controller.close();
  }
});

test('stale browser error acknowledgement also waits for the next authoritative operation', async () => {
  const errorEvent = deferred();
  const h = harness(() => errorEvent.promise);
  h.see(record());
  const old = h.starts[0];
  old.callbacks.onError();
  errorEvent.reject(Object.assign(new Error('stale'), { status: 409 }));
  await tick();
  assert.equal(h.controller.active, true);
  assert.equal(h.failures(), 0);
  assert.equal(h.controller.current, null);
  assert.equal(old.cancelled, 1);
  h.see(speech(2));
  assert.equal(h.starts.length, 2);
  h.controller.close();
});

test('authentication, unexpected response and network errors still fail closed', async () => {
  for (const status of [401, 403, 400, 500, undefined]) {
    const h = harness(async () => { throw Object.assign(new Error('rejected'), { status }); });
    h.see(record());
    await h.starts[0].callbacks.onStarted();
    assert.equal(h.controller.active, false, String(status));
    assert.equal(h.failures(), 1, String(status));
    assert.equal(h.starts[0].cancelled, 1, String(status));
  }
  // 409 is recoverable only on an operation request, never on the poll itself.
  const h = harness();
  await pollSequentially(async () => { throw Object.assign(new Error('rejected'), { status: 409 }); },
    h.controller, new AbortController().signal);
  assert.equal(h.controller.active, false);
  assert.equal(h.failures(), 1);
});

test('speech completion is real end, never enqueue/start, and waits for start acknowledgement', async () => {
  const ack = deferred();
  const h = harness((path, body) => body?.event === 'started' ? ack.promise : {});
  h.see(speech());
  assert.equal(h.calls.length, 0);
  assert.equal(h.starts[0].argument, 'Plain <assistant> text');
  const started = h.starts[0].callbacks.onStarted();
  h.starts[0].callbacks.onComplete();
  assert.deepEqual(h.calls.map(call => call.body.event), ['started']);
  ack.resolve({}); await started; await tick();
  assert.deepEqual(h.calls.map(call => call.body.event), ['started', 'ended']);
  h.starts[0].callbacks.onComplete();
  assert.equal(h.calls.length, 2);
  h.controller.close();
});

test('speech replacement ignores old completion and begins only the new text', async () => {
  const h = harness();
  h.see(speech());
  const old = h.starts[0];
  await old.callbacks.onStarted();
  h.see(speech(2));
  old.callbacks.onComplete();
  await tick();
  assert.equal(h.calls.filter(call => call.body.event === 'ended').length, 0);
  assert.equal(old.cancelled, 1);
  assert.equal(h.starts.length, 2);
  h.controller.close();
});

test('start/completion watchdog and browser errors report error and clear readiness', async () => {
  for (const kind of ['start-timeout', 'speech-timeout', 'capture-error', 'bad-pcm']) {
    const h = harness();
    h.see(kind === 'bad-pcm' ? record() : speech());
    if (kind === 'speech-timeout' || kind === 'bad-pcm') await h.starts[0].callbacks.onStarted();
    if (kind.includes('timeout')) [...h.timers.values()][0]();
    else if (kind === 'bad-pcm') h.starts[0].callbacks.onComplete(new Int16Array(480001));
    else h.starts[0].callbacks.onError();
    await tick();
    assert.equal(h.calls.at(-1).body.event, 'error', kind);
    assert.equal(h.failures(), 1, kind);
    assert.equal(h.timers.size, 0);
    assert.equal(h.controller.active, false);
  }
});

test('one sequential poll in flight; response after close is ignored', async () => {
  const response = deferred(), h = harness(), abort = new AbortController();
  let requests = 0, waits = 0;
  const running = pollSequentially(async () => { requests++; return response.promise; }, h.controller, abort.signal,
    async () => { waits++; h.controller.close(); });
  await tick();
  assert.equal(requests, 1);
  response.resolve(snapshot(record()));
  await running;
  assert.equal(waits, 1);
  assert.equal(h.starts.length, 1);
  assert.equal(h.starts[0].cancelled, 1);
  const later = deferred(), other = harness();
  const pending = pollSequentially(() => later.promise, other.controller, abort.signal);
  other.controller.close();
  later.resolve(snapshot(record()));
  await pending;
  assert.equal(other.starts.length, 0);
});

test('poll/network failure fails closed; fresh controller never revives previous callbacks', async () => {
  const h = harness();
  h.see(record());
  await pollSequentially(async () => { throw new Error('offline'); }, h.controller, new AbortController().signal);
  assert.equal(h.failures(), 1);
  assert.equal(h.starts[0].cancelled, 1);
  const fresh = harness();
  fresh.see();
  await h.starts[0].callbacks.onStarted();
  assert.equal(h.calls.length, 0);
  assert.equal(fresh.starts.length, 0);
  fresh.controller.close();
});
