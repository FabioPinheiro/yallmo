import test from 'node:test';
import assert from 'node:assert/strict';
import { recordingAdapter, speechAdapter } from './browser-audio.js';

class Utterance { constructor(text) { this.text = text; } }

test('speech adapter uses plain text, observes real events and suppresses cancelled callbacks', () => {
  const previous = globalThis.SpeechSynthesisUtterance;
  globalThis.SpeechSynthesisUtterance = Utterance;
  try {
    const events = [], queued = [];
    let cancellations = 0;
    const synth = { cancel: () => cancellations++, speak: utterance => queued.push(utterance) };
    const callbacks = { onStarted: () => events.push('started'), onComplete: () => events.push('ended'), onError: () => events.push('error') };
    const first = speechAdapter(synth, '<b>Not HTML</b>', callbacks);
    assert.equal(queued[0].text, '<b>Not HTML</b>');
    assert.deepEqual(events, []);
    const lateStart = queued[0].onstart, lateEnd = queued[0].onend;
    first.cancel(); lateStart(); lateEnd();
    assert.deepEqual(events, []);
    const second = speechAdapter(synth, 'Next', callbacks);
    second.utterance.onstart();
    assert.deepEqual(events, ['started']);
    second.utterance.onend();
    assert.deepEqual(events, ['started', 'ended']);
    assert.equal(second.utterance.onend, null);
    assert.equal(cancellations, 3);
    const third = speechAdapter(synth, 'Error', callbacks);
    third.utterance.onerror();
    assert.deepEqual(events, ['started', 'ended', 'error']);
  } finally { globalThis.SpeechSynthesisUtterance = previous; }
});

function recordingFixture() {
  const events = [], nodes = [];
  const node = () => ({ disconnected: 0, connected: [], disconnect() { this.disconnected++; }, connect(next) { this.connected.push(next); } });
  const source = node(), gain = { ...node(), gain: { value: 1 } };
  const context = { state: 'running', destination: {}, createMediaStreamSource: () => source, createGain: () => gain };
  const stream = { getAudioTracks: () => [{ readyState: 'live' }] };
  const callbacks = { onStarted: () => events.push('started'), onComplete: pcm => events.push(pcm), onError: () => events.push('error') };
  const MockNode = class {
    constructor(context, name, options) {
      Object.assign(this, node());
      this.name = name; this.options = options;
      this.messages = []; this.closed = false;
      this.port = { postMessage: message => this.messages.push(message), close: () => { this.closed = true; } };
      nodes.push(this);
    }
  };
  return { events, nodes, source, gain, context, stream, callbacks, MockNode };
}

test('capture adapter reports worklet start, uses muted graph and detaches on completion/cancel', () => {
  const previous = globalThis.AudioWorkletNode;
  const f = recordingFixture(); globalThis.AudioWorkletNode = f.MockNode;
  try {
    const handle = recordingAdapter(f.context, f.stream, 30000, f.callbacks);
    const node = f.nodes[0];
    assert.equal(f.gain.gain.value, 0);
    assert.equal(node.options.processorOptions.maxRecordingMs, 30000);
    assert.equal(f.events.length, 0);
    node.port.onmessage({ data: { type: 'started' } });
    handle.stop();
    assert.deepEqual(node.messages, ['stop']);
    node.port.onmessage({ data: { type: 'complete', buffer: new Int16Array([1, 2]).buffer, length: 2 } });
    assert.equal(f.events[0], 'started');
    assert.deepEqual(f.events[1], new Int16Array([1, 2]));
    assert.equal(f.source.disconnected, 1);
    assert.equal(node.closed, true);
    handle.cancel();
    const cancelled = recordingAdapter(f.context, f.stream, 1000, f.callbacks);
    const oldCallback = f.nodes[1].port.onmessage;
    cancelled.cancel();
    oldCallback({ data: { type: 'started' } });
    assert.equal(f.events.length, 2);
    assert.deepEqual(f.nodes[1].messages, ['cancel']);
    assert.equal(f.nodes[1].port.onmessage, null);
  } finally { globalThis.AudioWorkletNode = previous; }
});

test('capture adapter rejects suspended/dead input and handles processor failure', () => {
  const previous = globalThis.AudioWorkletNode;
  const f = recordingFixture(); globalThis.AudioWorkletNode = f.MockNode;
  try {
    f.context.state = 'suspended';
    assert.throws(() => recordingAdapter(f.context, f.stream, 1000, f.callbacks));
    f.context.state = 'running';
    assert.throws(() => recordingAdapter(f.context, { getAudioTracks: () => [{ readyState: 'ended' }] }, 1000, f.callbacks));
    recordingAdapter(f.context, f.stream, 1000, f.callbacks);
    f.nodes[0].onprocessorerror();
    assert.deepEqual(f.events, ['error']);
    assert.equal(f.nodes[0].closed, true);
  } finally { globalThis.AudioWorkletNode = previous; }
});
