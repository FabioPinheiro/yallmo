import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = (await readFile(new URL('./app.js', import.meta.url), 'utf8')).replace(/^import[^\n]+\n/gm, '');
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };

function setup({ token = true, speechEnds = true, permissionThrows = false } = {}) {
  const events = [], requests = [], contexts = [], streams = [], permissions = [], timers = new Map(), polls = [];
  const elements = new Map(['enable', 'disable', 'status', 'readiness'].map(id => [id, {
    disabled: id === 'disable', textContent: '', listeners: {}, addEventListener(name, callback) { this.listeners[name] = callback; }
  }]));
  const pageEvents = {}, docEvents = {};
  let timerId = 0, clientId = 0;
  const fakeContext = class {
    constructor() {
      this.state = 'running'; this.closed = 0;
      this.audioWorklet = { addModule: path => { events.push(['module', path]); return Promise.resolve(); } };
      contexts.push(this);
    }
    resume() { events.push(['resume']); return Promise.resolve(); }
    close() { this.closed++; this.state = 'closed'; return Promise.resolve(); }
  };
  const synth = { cancel: () => events.push(['speech-cancel']) };
  const window = { AudioContext: fakeContext, AudioWorkletNode: class {}, SpeechSynthesisUtterance: class {}, speechSynthesis: synth,
    addEventListener: (name, callback) => { pageEvents[name] = callback; } };
  const sandbox = {
    window, AudioContext: fakeContext, AbortController, Promise,
    setTimeout: (callback, ms) => { timers.set(++timerId, { callback, ms }); return timerId; },
    clearTimeout: id => timers.delete(id),
    document: { querySelector: selector => elements.get(selector.slice(1)), addEventListener: (name, callback) => { docEvents[name] = callback; } },
    location: { hash: token ? `#token=${'x'.repeat(43)}` : '', pathname: '/', hostname: '127.0.0.1', protocol: 'http:', search: '' },
    history: { replaceState: (...args) => events.push(['history', ...args]) },
    crypto: { randomUUID: () => `00000000-0000-4000-8000-${String(++clientId).padStart(12, '0')}` },
    navigator: { mediaDevices: { getUserMedia: () => {
      events.push(['permission']);
      if (permissionThrows) throw new Error('PRIVATE BROWSER ERROR');
      const permission = deferred(); permissions.push(permission); return permission.promise;
    } } },
    recordingAdapter: () => { throw new Error('No capture expected during setup'); },
    speechAdapter: (engine, text, callbacks) => {
      events.push(['speech-test', text]);
      if (speechEnds) { callbacks.onStarted(); callbacks.onComplete(); }
      return { cancel: () => events.push(['test-cancel']) };
    },
    makeRequest: options => async (path, body) => {
      requests.push({ options, path, body });
      if (path === '/api/connect') return { heartbeatMs: 1000, disconnectMs: 5000, maxRecordingMs: 30000, maxUploadBytes: 1048576 };
      return {};
    },
    OperationController: class { constructor(options) { this.options = options; this.active = true; } close() { this.active = false; } },
    pollSequentially: async (request, controller, signal) => { polls.push({ request, controller, signal }); }
  };
  vm.runInNewContext(source, sandbox);
  const stream = () => {
    const track = { readyState: 'live', stopped: 0, listeners: {}, stop() { this.stopped++; this.readyState = 'ended'; },
      addEventListener(name, callback) { this.listeners[name] = callback; } };
    const stream = { track, getTracks: () => [track], getAudioTracks: () => [track] };
    streams.push(stream); return stream;
  };
  return { events, requests, contexts, permissions, timers, polls, elements, pageEvents, docEvents, stream,
    click: name => { const el = elements.get(name); if (!el.disabled) el.listeners.click(); },
    fire: ms => { const timer = [...timers].find(([, value]) => value.ms === ms); assert.ok(timer); timers.delete(timer[0]); timer[1].callback(); }
  };
}

test('mock page: activation happens inside gesture before permission resolves; ready only after connect', async () => {
  const f = setup();
  assert.deepEqual(f.events[0], ['history', null, '', '/']);
  assert.equal(f.requests.length, 0);
  f.click('enable');
  assert.deepEqual(f.events.slice(1).map(event => event[0]), ['resume', 'speech-test', 'permission', 'module']);
  assert.equal(f.requests.length, 0);
  assert.equal(f.elements.get('readiness').textContent, 'Not ready');
  const stream = f.stream();
  f.permissions[0].resolve(stream); await tick();
  assert.equal(f.requests[0].path, '/api/connect');
  assert.equal(f.elements.get('readiness').textContent, 'Connected');
  assert.equal(f.polls.length, 1);
  assert.equal(stream.track.stopped, 0);
  f.click('disable'); await tick();
  assert.equal(stream.track.stopped, 1);
  assert.equal(f.contexts[0].closed, 1);
  assert.equal(f.polls[0].signal.aborted, true);
  assert.equal(f.requests.at(-1).path, '/api/disconnect');
  assert.equal(f.requests.at(-1).options.signal, undefined);
  assert.equal(f.elements.get('readiness').textContent, 'Not ready');
  assert.equal(f.timers.size, 0);
});

test('mock page: disable during permission request stops a late stream without connecting', async () => {
  const f = setup();
  f.click('enable'); f.click('disable');
  const late = f.stream();
  f.permissions[0].resolve(late); await tick();
  assert.equal(late.track.stopped, 1);
  assert.equal(f.contexts[0].closed, 1);
  assert.equal(f.requests.length, 0);
  assert.equal(f.timers.size, 0);
});

test('mock page: permission timeout and speech activation timeout require fresh setup and discard late permission', async () => {
  for (const ms of [20000, 4500]) {
    const f = setup({ speechEnds: ms !== 4500 });
    f.click('enable'); f.fire(ms); await tick();
    assert.equal(f.elements.get('readiness').textContent, 'Not ready');
    const late = f.stream(); f.permissions[0].resolve(late); await tick();
    assert.equal(late.track.stopped, 1);
    assert.equal(f.requests.length, 0);
    assert.equal(f.timers.size, 0);
    assert.equal(f.elements.get('enable').disabled, false);
  }
});

test('mock page: permission rejection (including synchronous throw) never exposes browser error text', async () => {
  for (const sync of [false, true]) {
    const f = setup({ permissionThrows: sync });
    f.click('enable');
    if (!sync) f.permissions[0].reject(new Error('PRIVATE BROWSER ERROR'));
    await tick();
    assert.equal(f.requests.length, 0);
    assert.equal(f.contexts[0].closed, 1);
    assert.ok(!f.elements.get('status').textContent.includes('PRIVATE'));
    assert.equal(f.timers.size, 0);
  }
});

test('mock page: pagehide, freeze, revoked track and suspended context stop all readiness/resources', async () => {
  for (const failure of ['pagehide', 'freeze', 'track', 'context']) {
    const f = setup(); f.click('enable');
    const stream = f.stream(); f.permissions[0].resolve(stream); await tick();
    if (failure === 'pagehide') f.pageEvents.pagehide();
    if (failure === 'freeze') f.docEvents.freeze();
    if (failure === 'track') stream.track.listeners.ended();
    if (failure === 'context') { f.contexts[0].state = 'suspended'; f.contexts[0].onstatechange(); }
    assert.equal(stream.track.stopped, 1, failure);
    assert.equal(f.contexts[0].closed, 1, failure);
    assert.equal(f.polls[0].controller.active, false, failure);
    assert.equal(f.elements.get('readiness').textContent, 'Not ready', failure);
  }
});

test('mock page: fresh Enable cancels prior session and uses a fresh client, missing token cannot enable', async () => {
  const missing = setup({ token: false }); missing.click('enable');
  assert.equal(missing.contexts.length, 0);
  const f = setup(); f.click('enable');
  const first = f.stream(); f.permissions[0].resolve(first); await tick();
  f.click('enable');
  assert.equal(first.track.stopped, 1);
  assert.equal(f.polls[0].controller.active, false);
  const next = f.stream(); f.permissions[1].resolve(next); await tick();
  const connects = f.requests.filter(request => request.path === '/api/connect');
  assert.equal(connects.length, 2);
  assert.notEqual(connects[0].body.clientId, connects[1].body.clientId);
  f.click('disable');
});
