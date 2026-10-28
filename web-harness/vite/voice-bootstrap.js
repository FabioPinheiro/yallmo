import captureUrl from './capture-worklet.js?worker&url';
import { available, start } from './voice-recorder.js';

// Evaluated before the Scala.js entry point; the Scala facade remains mockable in Node tests.
globalThis.yallmoVoiceRecorder = {
  available: () => available(window),
  start: callbacks => start(window, captureUrl, callbacks)
};
