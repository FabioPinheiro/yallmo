// Browser adapters keep the controller independent of DOM and audio APIs.
export function recordingAdapter(context, stream, maxRecordingMs, callbacks) {
  if (context.state !== 'running' || stream.getAudioTracks().some(track => track.readyState !== 'live')) {
    throw new Error('Microphone unavailable');
  }
  const source = context.createMediaStreamSource(stream);
  const node = new AudioWorkletNode(context, 'browser-capture', {
    numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1],
    channelCountMode: 'max', processorOptions: { maxRecordingMs }
  });
  const mute = context.createGain();
  mute.gain.value = 0;
  let live = true;
  const detach = () => {
    source.disconnect();
    node.disconnect();
    mute.disconnect();
    node.port.onmessage = null;
    node.onprocessorerror = null;
    node.port.close();
  };
  const cancel = () => {
    if (!live) return;
    live = false;
    node.port.postMessage('cancel');
    detach();
  };
  node.port.onmessage = ({ data }) => {
    if (!live) return;
    if (data.type === 'started') callbacks.onStarted();
    else if (data.type === 'complete') {
      live = false;
      detach();
      if (!(data.buffer instanceof ArrayBuffer) || !Number.isInteger(data.length) ||
          data.length < 1 || data.length > maxRecordingMs * 16 ||
          data.buffer.byteLength < data.length * 2 || data.buffer.byteLength > maxRecordingMs * 32) {
        callbacks.onError();
      } else callbacks.onComplete(new Int16Array(data.buffer, 0, data.length));
    } else { cancel(); callbacks.onError(); }
  };
  node.onprocessorerror = () => { cancel(); callbacks.onError(); };
  try { source.connect(node); node.connect(mute); mute.connect(context.destination); }
  catch (error) { cancel(); throw error; }
  return {
    stop: () => { if (live) node.port.postMessage('stop'); },
    cancel
  };
}

export function speechAdapter(synthesis, text, callbacks) {
  synthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  let live = true;
  const detach = () => {
    utterance.onstart = null;
    utterance.onend = null;
    utterance.onerror = null;
  };
  utterance.onstart = () => { if (live) callbacks.onStarted(); };
  utterance.onend = () => {
    if (!live) return;
    live = false;
    detach();
    callbacks.onComplete();
  };
  utterance.onerror = () => {
    if (!live) return;
    live = false;
    detach();
    callbacks.onError();
  };
  // Retain the utterance through this handle until completion/cancellation.
  const handle = {
    utterance,
    stop: () => {},
    cancel: () => {
      const wasLive = live;
      live = false;
      detach();
      if (wasLive) synthesis.cancel();
    }
  };
  try { synthesis.speak(utterance); }
  catch (error) { handle.cancel(); throw error; }
  return handle;
}
