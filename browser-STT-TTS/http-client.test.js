import test from 'node:test';
import assert from 'node:assert/strict';
import { makeRequest } from './http-client.js';

const credentials = { token: 'a'.repeat(43), clientId: 'test-client-identifier' };

test('API credentials only in headers; bounded raw WAV and JSON use same-origin/no-store transport', async () => {
  const calls = [];
  const request = makeRequest({ ...credentials, fetchImpl: async (path, options) => {
    calls.push({ path, options }); return { ok: true, json: async () => ({}) };
  } });
  await request('/api/connect', { clientId: credentials.clientId });
  await request('/api/poll');
  const wav = new ArrayBuffer(44);
  await request('/api/audio/operation-id', wav);
  await request('/api/disconnect');
  assert.deepEqual(calls.map(call => call.path), ['/api/connect', '/api/poll', '/api/audio/operation-id', '/api/disconnect']);
  for (const { options } of calls) {
    assert.equal(options.headers.Authorization, `Bearer ${credentials.token}`);
    assert.equal(options.headers['X-Browser-Client'], credentials.clientId);
    assert.equal(options.cache, 'no-store');
    assert.equal(options.mode, 'same-origin');
    assert.equal(options.credentials, 'omit');
    assert.equal(options.redirect, 'error');
    assert.equal(options.referrerPolicy, 'no-referrer');
  }
  assert.equal(calls[0].options.body, JSON.stringify({ clientId: credentials.clientId }));
  assert.equal(calls[1].options.method, 'GET');
  assert.equal(calls[2].options.body, wav);
  assert.equal(calls[2].options.headers['Content-Type'], 'audio/wav');
  assert.equal(calls[3].options.method, 'POST');
  assert.equal(calls[3].options.body, undefined);
});

test('non-JSON transport errors fail closed without trying to decode or echo response', async () => {
  let decoded = false;
  const request = makeRequest({ ...credentials, fetchImpl: async () => ({
    ok: false, status: 413, json: () => { decoded = true; throw new Error('empty response'); }
  }) });
  await assert.rejects(request('/api/poll'), error => error.status === 413 && !error.message.includes('empty'));
  assert.equal(decoded, false);
});

test('request timeout and session/operation cancellation abort in-flight work', async () => {
  for (const cancellation of ['timeout', 'session', 'operation']) {
    const session = new AbortController(), op = new AbortController();
    let fetchSignal;
    const request = makeRequest({ ...credentials, signal: session.signal, timeoutMs: 10,
      fetchImpl: async (path, options) => {
        fetchSignal = options.signal;
        return new Promise((resolve, reject) => {
          const fail = () => reject(new Error('aborted'));
          fetchSignal.addEventListener('abort', fail, { once: true });
          if (fetchSignal.aborted) fail();
        });
      }
    });
    const pending = request('/api/event', { event: 'started' }, op.signal);
    if (cancellation === 'session') session.abort();
    if (cancellation === 'operation') op.abort();
    await assert.rejects(pending, /aborted/);
    assert.equal(fetchSignal.aborted, true);
  }
});

test('pre-aborted requests and malformed successful JSON cannot be treated as success', async () => {
  const session = new AbortController(); session.abort();
  const cancelled = makeRequest({ ...credentials, signal: session.signal, fetchImpl: async (path, options) => {
    assert.equal(options.signal.aborted, true); throw new Error('already aborted');
  } });
  await assert.rejects(cancelled('/api/poll'), /aborted/);
  const malformed = makeRequest({ ...credentials, fetchImpl: async () => ({ ok: true, json: async () => { throw new SyntaxError('invalid'); } }) });
  await assert.rejects(malformed('/api/poll'), SyntaxError);
});
