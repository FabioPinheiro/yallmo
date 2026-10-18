import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { BoundedResampler, pcm16, encodeWav, MAX_SAMPLES, MAX_WAV_BYTES } from './audio-core.js';

function convert(rate, channels, blocks = [128]) {
  const resampler = new BoundedResampler(rate);
  for (let at = 0, block = 0; at < channels[0].length; block++) {
    const end = Math.min(channels[0].length, at + blocks[block % blocks.length]);
    resampler.push(channels.map(channel => channel.subarray(at, end)));
    at = end;
  }
  return resampler.finish();
}
const signal = (rate, hz, seconds = 0.25) => Float32Array.from(
  { length: Math.floor(rate * seconds) }, (_, i) => 0.5 * Math.sin(2 * Math.PI * hz * i / rate));
const rms = samples => Math.sqrt(samples.slice(100, -100).reduce((sum, value) => sum + value * value, 0) / (samples.length - 200));

test('PCM clamps endpoints, silence and nonfinite inputs', () => {
  assert.deepEqual([-2, -1, 0, 1, 2, NaN, Infinity].map(pcm16), [-32768, -32768, 0, 32767, 32767, 0, 0]);
});

test('canonical WAV has exact little-endian header, sizes and PCM', () => {
  const wav = encodeWav(new Int16Array([-32768, 0, 32767]));
  const bytes = Buffer.from(wav);
  assert.equal(bytes.toString('ascii', 0, 4), 'RIFF');
  assert.equal(bytes.readUInt32LE(4), wav.byteLength - 8);
  assert.equal(bytes.toString('ascii', 8, 16), 'WAVEfmt ');
  assert.equal(bytes.readUInt32LE(16), 16);
  assert.equal(bytes.readUInt16LE(20), 1);
  assert.equal(bytes.readUInt16LE(22), 1);
  assert.equal(bytes.readUInt32LE(24), 16000);
  assert.equal(bytes.readUInt32LE(28), 32000);
  assert.equal(bytes.readUInt16LE(32), 2);
  assert.equal(bytes.readUInt16LE(34), 16);
  assert.equal(bytes.toString('ascii', 36, 40), 'data');
  assert.equal(bytes.readUInt32LE(40), 6);
  assert.equal(bytes.readInt16LE(44), -32768);
  assert.equal(bytes.readInt16LE(48), 32767);
  assert.equal(encodeWav(new Int16Array(MAX_SAMPLES)).byteLength, MAX_WAV_BYTES);
  for (const invalid of [new Int16Array(), new Int16Array(MAX_SAMPLES + 1), new Float32Array(1)]) {
    assert.throws(() => encodeWav(invalid), RangeError);
  }
});

for (const rate of [8000, 16000, 44100, 48000, 96000]) {
  test(`${rate} Hz native input: stable phase across variable block boundaries and mono downmix`, () => {
    const left = signal(rate, 1000);
    const right = Float32Array.from(left, value => value * 0.5);
    const a = convert(rate, [left, right], [1, 127, 251, 64]);
    const b = convert(rate, [left, right], [4096]);
    assert.deepEqual(a, b);
    assert.equal(a.length, Math.floor(left.length * 16000 / rate));
    assert.ok(rms(a) > 8000 && rms(a) < 9000, `RMS ${rms(a)}`);
    const opposite = Float32Array.from(left, value => -value);
    assert.ok(convert(rate, [left, opposite]).every(value => value === 0));
  });
}

for (const rate of [44100, 48000]) {
  test(`${rate} Hz native input: anti-alias filter rejects 12 kHz while preserving speech band`, () => {
    const low = rms(convert(rate, [signal(rate, 1000)]));
    const high = rms(convert(rate, [signal(rate, 12000)]));
    assert.ok(high / low < 0.01, `stop-band ratio ${high / low}`);
  });
}

test('capture bounds enforced during processing, not by a main-thread timer', () => {
  const resampler = new BoundedResampler(48000);
  const block = new Float32Array(1024).fill(0.25);
  for (let i = 0; i < 1500; i++) resampler.push([block]);
  assert.equal(resampler.frames, 1440000);
  assert.equal(resampler.samples.length, MAX_SAMPLES);
  assert.equal(resampler.count, MAX_SAMPLES);
  assert.equal(resampler.finished, true);
  const result = resampler.finish();
  resampler.push([block]);
  assert.deepEqual(resampler.finish(), result);
  assert.equal(resampler.frames, 1440000);
});

test('empty/short capture and invalid bounds are handled without fabricated audio', () => {
  assert.equal(new BoundedResampler(48000).finish().length, 0);
  const short = new BoundedResampler(48000);
  short.push([new Float32Array(2)]);
  assert.equal(short.finish().length, 0);
  for (const rate of [NaN, 0, 192001]) assert.throws(() => new BoundedResampler(rate), RangeError);
  for (const cap of [0, MAX_SAMPLES + 1, 1.2]) assert.throws(() => new BoundedResampler(48000, cap), RangeError);
});

async function worklet() {
  const messages = [];
  let Capture;
  const source = (await readFile(new URL('./capture-worklet.js', import.meta.url), 'utf8')).replace(/^import[^\n]+\n/, '');
  vm.runInNewContext(source, {
    BoundedResampler, OUTPUT_RATE: 16000, sampleRate: 44100,
    AudioWorkletProcessor: class { constructor() { this.port = { postMessage: value => messages.push(value) }; } },
    registerProcessor: (name, ctor) => { assert.equal(name, 'browser-capture'); Capture = ctor; }
  });
  return { capture: new Capture({ processorOptions: { maxRecordingMs: 1000 } }), messages };
}

test('worklet reports actual processing start, produces silent output and caps native frames', async () => {
  const { capture, messages } = await worklet();
  assert.equal(messages.length, 0);
  const input = new Float32Array(251).fill(0.25);
  const output = new Float32Array(251).fill(1);
  while (!capture.closed) capture.process([[input, input]], [[output]]);
  assert.ok(output.every(value => value === 0));
  assert.deepEqual(messages.map(message => message.type), ['started', 'complete']);
  assert.equal(messages[1].length, 16000);
  assert.equal(messages[1].buffer.byteLength, 32000);
  capture.port.onmessage?.({ data: 'stop' });
  assert.equal(messages.length, 2);
});

test('worklet stop flushes once; cancellation discards without completion', async () => {
  const { capture, messages } = await worklet();
  capture.process([[new Float32Array(441)]], []);
  capture.port.onmessage({ data: 'stop' });
  assert.equal(messages[1].length, 160);
  const cancelled = await worklet();
  cancelled.capture.process([[new Float32Array(128)]], []);
  cancelled.capture.port.onmessage({ data: 'cancel' });
  assert.equal(cancelled.capture.process([], []), false);
  assert.deepEqual(cancelled.messages.map(message => message.type), ['started']);
  assert.equal(cancelled.capture.resampler, null);
});
