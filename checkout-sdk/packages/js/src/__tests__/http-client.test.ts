import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { HttpClient } from '../http-client';

describe('HttpClient', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('makes GET request with auth header', async () => {
    const mockResponse = { id: 'test' };
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(mockResponse),
    });

    const client = new HttpClient({ baseUrl: 'https://api.test.com', sessionToken: 'tok_123' });
    const result = await client.get('/v1/session');

    expect(result).toEqual(mockResponse);
    expect(globalThis.fetch).toHaveBeenCalledWith(
      'https://api.test.com/v1/session',
      expect.objectContaining({
        method: 'GET',
        headers: expect.objectContaining({
          Authorization: 'Bearer tok_123',
        }),
      }),
    );
  });

  it('makes POST request with JSON body', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ id: 'ptok_1' }),
    });

    const client = new HttpClient({ baseUrl: 'https://api.test.com' });
    client.setSessionToken('tok_abc');
    await client.post('/v1/tokenize', { type: 'card' });

    expect(globalThis.fetch).toHaveBeenCalledWith(
      'https://api.test.com/v1/tokenize',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ type: 'card' }),
      }),
    );
  });

  it('throws NexusPayError on HTTP 4xx', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 429,
      statusText: 'Too Many Requests',
      json: () => Promise.resolve({
        error: { type: 'rate_limit_error', code: 'tokenization_rate_limit', message: 'Rate limited' },
      }),
    });

    const client = new HttpClient({ baseUrl: 'https://api.test.com' });
    client.setSessionToken('tok_abc');

    await expect(client.post('/v1/tokenize', {})).rejects.toEqual({
      type: 'rate_limit_error',
      code: 'tokenization_rate_limit',
      message: 'Rate limited',
    });
  });

  it('does NOT retry on HTTP 4xx/5xx', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      statusText: 'Bad Request',
      json: () => Promise.resolve({}),
    });

    const client = new HttpClient({ baseUrl: 'https://api.test.com' });
    client.setSessionToken('tok_abc');

    await expect(client.get('/v1/session')).rejects.toBeDefined();
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
  });

  it('retries once on network error (TypeError)', async () => {
    globalThis.fetch = vi.fn()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce({
        ok: true,
        json: () => Promise.resolve({ id: 'ok' }),
      });

    const client = new HttpClient({ baseUrl: 'https://api.test.com' });
    client.setSessionToken('tok_abc');
    const result = await client.get('/v1/session');

    expect(result).toEqual({ id: 'ok' });
    expect(globalThis.fetch).toHaveBeenCalledTimes(2);
  });

  it('throws timeout error when request exceeds timeout', async () => {
    globalThis.fetch = vi.fn().mockImplementation((_url, init) => {
      return new Promise((_resolve, reject) => {
        // Simulate the AbortController aborting
        if (init?.signal) {
          init.signal.addEventListener('abort', () => {
            reject(new DOMException('The operation was aborted', 'AbortError'));
          });
        }
      });
    });

    const client = new HttpClient({ baseUrl: 'https://api.test.com', timeout: 50 });
    client.setSessionToken('tok_abc');

    await expect(client.get('/v1/session')).rejects.toEqual(
      expect.objectContaining({
        type: 'network_error',
        code: 'timeout',
      }),
    );
  });
});
