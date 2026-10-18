export function makeRequest({ token, clientId, signal, fetchImpl = globalThis.fetch, timeoutMs = 4000 }) {
  return async (path, body, operationSignal) => {
    const abort = new AbortController();
    const cancel = () => abort.abort();
    const signals = [signal, operationSignal].filter(Boolean);
    for (const source of signals) {
      source.addEventListener('abort', cancel, { once: true });
      if (source.aborted) cancel();
    }
    const timer = setTimeout(cancel, timeoutMs);
    try {
      const headers = { Authorization: `Bearer ${token}`, 'X-Browser-Client': clientId };
      const binary = body instanceof ArrayBuffer;
      if (body !== undefined) headers['Content-Type'] = binary ? 'audio/wav' : 'application/json';
      const response = await fetchImpl(path, {
        method: path === '/api/poll' ? 'GET' : 'POST',
        headers, body: body === undefined ? undefined : binary ? body : JSON.stringify(body),
        signal: abort.signal, cache: 'no-store', credentials: 'omit', redirect: 'error',
        mode: 'same-origin', referrerPolicy: 'no-referrer'
      });
      // Transport errors (notably 413) need not contain JSON. Never echo response text.
      if (!response.ok) {
        const error = new Error('Local audio request rejected');
        error.status = response.status;
        throw error;
      }
      return await response.json();
    } finally {
      clearTimeout(timer);
      for (const source of signals) source.removeEventListener('abort', cancel);
    }
  };
}
