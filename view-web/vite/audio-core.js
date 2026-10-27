export const OUTPUT_RATE = 16000;
export const MAX_SAMPLES = 480000;
export const MAX_WAV_BYTES = 960044;

const TAPS = 64;
const HALF = TAPS / 2;
const PHASES = 256;

export function pcm16(sample) {
  const value = Number.isFinite(sample) ? Math.max(-1, Math.min(1, sample)) : 0;
  return Math.round(value * (value < 0 ? 32768 : 32767));
}

// Windowed-sinc low-pass interpolation. Filter history and fractional phase survive
// render blocks; both native frames and output storage are capped inside the worklet.
export class BoundedResampler {
  constructor(inputRate, maxSamples = MAX_SAMPLES) {
    if (!Number.isFinite(inputRate) || inputRate < 8000 || inputRate > 192000 ||
        !Number.isInteger(maxSamples) || maxSamples < 1 || maxSamples > MAX_SAMPLES) {
      throw new RangeError('Unsupported capture bounds');
    }
    this.inputRate = inputRate;
    this.maxSamples = maxSamples;
    this.maxFrames = Math.ceil(maxSamples * inputRate / OUTPUT_RATE);
    this.frames = 0;
    this.index = -1;
    this.count = 0;
    this.finished = false;
    this.samples = new Int16Array(maxSamples);
    this.ring = new Float32Array(TAPS * 2);
    this.filters = new Float32Array(PHASES * TAPS);
    const cutoff = 0.90 * Math.min(1, OUTPUT_RATE / inputRate);
    for (let phase = 0; phase < PHASES; phase++) {
      let sum = 0;
      for (let tap = 0; tap < TAPS; tap++) {
        const distance = tap - HALF + 1 - phase / PHASES;
        const x = Math.PI * cutoff * distance;
        const sinc = x === 0 ? 1 : Math.sin(x) / x;
        const window = 0.42 + 0.5 * Math.cos(Math.PI * distance / HALF) +
          0.08 * Math.cos(2 * Math.PI * distance / HALF);
        const value = cutoff * sinc * window;
        this.filters[phase * TAPS + tap] = value;
        sum += value;
      }
      for (let tap = 0; tap < TAPS; tap++) this.filters[phase * TAPS + tap] /= sum;
    }
  }

  push(channels) {
    if (this.finished || !channels.length || !channels[0].length) return;
    const length = channels[0].length;
    if (channels.some(channel => channel.length !== length)) throw new RangeError('Unequal channels');
    for (let frame = 0; frame < length && this.frames < this.maxFrames; frame++) {
      let mono = 0;
      for (const channel of channels) mono += Number.isFinite(channel[frame]) ? channel[frame] : 0;
      this.frames++;
      this.append(mono / channels.length, this.maxSamples);
    }
    if (this.frames === this.maxFrames) this.finish();
  }

  append(value, target) {
    this.index++;
    this.ring[this.index % this.ring.length] = value;
    while (this.count < target) {
      // Derive phase from the absolute output index, never per-block rounding.
      const position = this.count * this.inputRate / OUTPUT_RATE;
      const center = Math.floor(position);
      if (center + HALF > this.index) break;
      const phase = Math.min(PHASES - 1, Math.floor((position - center) * PHASES));
      let sample = 0;
      for (let tap = 0; tap < TAPS; tap++) {
        const at = center - HALF + 1 + tap;
        if (at >= 0) sample += this.ring[at % this.ring.length] * this.filters[phase * TAPS + tap];
      }
      this.samples[this.count++] = pcm16(sample);
    }
  }

  finish() {
    if (!this.finished) {
      const target = Math.min(this.maxSamples, Math.floor(this.frames * OUTPUT_RATE / this.inputRate));
      while (this.count < target) this.append(0, target);
      this.finished = true;
    }
    return this.samples.subarray(0, this.count);
  }
}

export function encodeWav(samples) {
  if (!(samples instanceof Int16Array) || samples.length < 1 || samples.length > MAX_SAMPLES) {
    throw new RangeError('WAV requires bounded nonempty PCM');
  }
  const buffer = new ArrayBuffer(44 + samples.length * 2);
  const view = new DataView(buffer);
  const text = (at, value) => [...value].forEach((char, i) => view.setUint8(at + i, char.charCodeAt(0)));
  text(0, 'RIFF');
  view.setUint32(4, buffer.byteLength - 8, true);
  text(8, 'WAVE');
  text(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, OUTPUT_RATE, true);
  view.setUint32(28, OUTPUT_RATE * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  text(36, 'data');
  view.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) view.setInt16(44 + i * 2, samples[i], true);
  return buffer;
}
