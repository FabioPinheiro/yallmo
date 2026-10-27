import { BoundedResampler, OUTPUT_RATE } from './audio-core.js';

class BrowserCapture extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const maxMs = options.processorOptions.maxRecordingMs;
    if (!Number.isInteger(maxMs) || maxMs < 1000 || maxMs > 30000) throw new RangeError('Invalid duration');
    this.resampler = new BoundedResampler(sampleRate, Math.floor(maxMs * OUTPUT_RATE / 1000));
    this.started = false;
    this.closed = false;
    this.port.onmessage = ({ data }) => {
      if (data === 'stop') this.complete();
      if (data === 'cancel') this.discard();
    };
  }

  discard() {
    this.closed = true;
    this.resampler = null;
    this.port.onmessage = null;
  }

  complete() {
    if (this.closed) return;
    const pcm = this.resampler.finish();
    const buffer = pcm.buffer;
    const length = pcm.length;
    this.discard();
    this.port.postMessage({ type: 'complete', buffer, length }, [buffer]);
  }

  process(inputs, outputs) {
    // A connected silent output keeps processing alive without microphone feedback.
    for (const output of outputs) for (const channel of output) channel.fill(0);
    if (this.closed) return false;
    try {
      const channels = inputs[0];
      if (channels?.length && channels[0].length) {
        this.resampler.push(channels);
        if (!this.started) {
          this.started = true;
          this.port.postMessage({ type: 'started' });
        }
        if (this.resampler.finished) this.complete();
      }
    } catch {
      this.discard();
      this.port.postMessage({ type: 'error' });
    }
    return !this.closed;
  }
}

registerProcessor('browser-capture', BrowserCapture);
