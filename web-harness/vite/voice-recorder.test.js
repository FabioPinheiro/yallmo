import test from 'node:test';
import assert from 'node:assert/strict';
import { available, start, MAX_RECORDING_MS } from './voice-recorder.js';

const flush = async () => { for (let i = 0; i < 5; i++) await Promise.resolve(); };
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return {promise, resolve, reject}; };
function harness(permission) {
  const timers = new Map();
  const events = [];
  const result = {started:0, complete:[], errors:[], stops:0, cancels:0, contexts:[], timers, events};
  let next = 0;
  const listeners = new Map();
  const lifecycle = new Map();
  const track = {
    readyState:'live', stopped:0,
    addEventListener(name, action) { listeners.set(name, action); },
    removeEventListener(name) { listeners.delete(name); },
    stop() { this.stopped++; this.readyState='ended'; }
  };
  const stream = {getTracks:()=>[track], getAudioTracks:()=>[track]};
  const env = {
    isSecureContext:true, AudioWorkletNode:class {},
    addEventListener(name, action) { lifecycle.set(name, action); },
    removeEventListener(name) { lifecycle.delete(name); },
    document: {
      addEventListener(name, action) { lifecycle.set(name, action); },
      removeEventListener(name) { lifecycle.delete(name); }
    },
    navigator:{mediaDevices:{getUserMedia() { events.push('permission'); return permission?.promise ?? Promise.resolve(stream); }}},
    AudioContext:class {
      state='running'; closed=0; onstatechange=null;
      audioWorklet={addModule:url=>{events.push(url); return Promise.resolve();}};
      constructor() { result.contexts.push(this); }
      resume() { events.push('resume'); return Promise.resolve(); }
      close() { this.closed++; this.state='closed'; return Promise.resolve(); }
    },
    setTimeout(action, ms) { const id = ++next; timers.set(id,{action,ms}); return id; },
    clearTimeout(id) { timers.delete(id); }
  };
  const callbacks = {
    onStarted:()=>result.started++,
    onComplete:(bytes, limited)=>result.complete.push({bytes,limited}),
    onError:message=>result.errors.push(message)
  };
  result.begin = () => start(env, '/capture.js', callbacks, (context, incoming, max, cb) => {
    assert.equal(incoming,stream); assert.equal(max,30000);
    result.capture = cb;
    return {stop(){result.stops++;},cancel(){result.cancels++;}};
  });
  result.fire = ms => { const item = [...timers].find(([,v])=>v.ms===ms); assert.ok(item, `No timer ${ms}`); timers.delete(item[0]); item[1].action(); };
  result.ended = () => listeners.get('ended')?.();
  return Object.assign(result,{env,track,stream,lifecycle});
}

test('availability requires secure context, microphone and worklet APIs', () => {
  const h=harness(); assert.equal(available(h.env),true);
  h.env.isSecureContext=false; assert.equal(available(h.env),false);
  h.env.isSecureContext=true; h.env.AudioWorkletNode=undefined; assert.equal(available(h.env),false);
});

test('setup requests resume and microphone in the click, then explicit stop encodes WAV and releases resources', async () => {
  const h=harness(); const handle=h.begin();
  assert.deepEqual(h.events,['resume','permission','/capture.js']);
  await flush(); assert.equal(h.started,0);
  h.capture.onStarted(); h.capture.onStarted(); assert.equal(h.started,1);
  handle.stop(); handle.stop(); assert.equal(h.stops,1);
  h.capture.onComplete(new Int16Array([1,-2,3]));
  assert.equal(h.complete.length,1); assert.equal(h.complete[0].limited,false);
  const bytes=h.complete[0].bytes;
  assert.equal(new TextDecoder().decode(bytes.slice(0,4)),'RIFF');
  assert.equal(new DataView(bytes.buffer).getUint32(24,true),16000);
  assert.equal(bytes.length,50);
  assert.equal(h.track.stopped,1); assert.equal(h.contexts[0].closed,1); assert.equal(h.timers.size,0);
  handle.cancel(); h.capture.onError(); assert.equal(h.errors.length,0);
});

test('30-second limit explicitly stops and signals completion without restarting', async () => {
  const h=harness(); h.begin(); await flush(); h.capture.onStarted();
  h.fire(MAX_RECORDING_MS); assert.equal(h.stops,1);
  h.capture.onComplete(new Int16Array(480000));
  assert.equal(h.complete[0].bytes.length,960044); assert.equal(h.complete[0].limited,true);
  assert.equal(h.track.stopped,1); assert.equal(h.contexts.length,1);
});

test('worklet-native duration completion is also identified as the limit', async () => {
  const h=harness(); h.begin(); await flush(); h.capture.onStarted();
  h.capture.onComplete(new Int16Array(480000)); assert.equal(h.complete[0].limited,true);
});

test('discard during permission stops late tracks and never constructs a capture', async () => {
  const permission=deferred(); const h=harness(permission); const handle=h.begin(); handle.cancel();
  permission.resolve(h.stream); await flush();
  assert.equal(h.track.stopped,1); assert.equal(h.capture,undefined);
  assert.equal(h.complete.length,0); assert.equal(h.errors.length,0); assert.equal(h.timers.size,0);
});

test('permission denial, ignored permission and missing worklet start fail closed', async () => {
  const denied=deferred(); const h=harness(denied); h.begin(); denied.reject(new Error('private device details')); await flush();
  assert.equal(h.errors.length,1); assert.ok(!h.errors[0].includes('private'));
  const pending=deferred(); const ignored=harness(pending); ignored.begin(); ignored.fire(20000);
  pending.resolve(ignored.stream); await flush();
  assert.equal(ignored.track.stopped,1); assert.equal(ignored.capture,undefined); assert.equal(ignored.errors.length,1);
  const silent=harness(); silent.begin(); await flush(); silent.fire(20000);
  assert.equal(silent.track.stopped,1); assert.equal(silent.errors.length,1);
});

test('discard and late callbacks cannot produce audio or restart capture', async () => {
  const h=harness(); const handle=h.begin(); await flush(); h.capture.onStarted(); handle.cancel();
  h.capture.onComplete(new Int16Array(16)); h.capture.onStarted(); h.capture.onError();
  assert.equal(h.complete.length,0); assert.equal(h.errors.length,0); assert.equal(h.started,1);
  assert.equal(h.track.stopped,1); assert.equal(h.timers.size,0);
});

test('page exit and freezing release tracks, timers and lifecycle listeners', async () => {
  for (const event of ['pagehide','freeze']) {
    const h=harness(); h.begin(); await flush(); h.capture.onStarted();
    h.lifecycle.get(event)();
    assert.equal(h.track.stopped,1); assert.equal(h.timers.size,0); assert.equal(h.lifecycle.size,0);
    assert.equal(h.errors.length,1); assert.equal(h.complete.length,0);
  }
});

test('ended microphone, suspended context and stalled stop are bounded failures', async () => {
  for (const cause of ['ended','suspended','stalled']) {
    const h=harness(); const handle=h.begin(); await flush(); h.capture.onStarted();
    if (cause==='ended') h.ended();
    else if (cause==='suspended') { h.contexts[0].state='suspended'; h.contexts[0].onstatechange(); }
    else { handle.stop(); h.fire(4500); }
    assert.equal(h.errors.length,1); assert.equal(h.track.stopped,1); assert.equal(h.complete.length,0);
  }
});

