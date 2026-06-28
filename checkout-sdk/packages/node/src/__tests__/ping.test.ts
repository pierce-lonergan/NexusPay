import { describe, it, expect, vi } from 'vitest';
import { NexusPay } from '../client';
import { createTestTransport } from '../testing';

const BASE = 'https://api.test.nexuspay.io';
const API_KEY = 'sk_test_x';

describe('E3 client.ping()', () => {
  it('parses {ok, livemode, api_version} -> typed {ok, livemode, apiVersion} (snake->camel)', async () => {
    const transport = createTestTransport({
      'GET /v1/ping': () => ({
        status: 200,
        body: { ok: true, livemode: false, api_version: '2026-06-16' },
      }),
    });
    const client = new NexusPay({ apiKey: API_KEY, baseUrl: BASE, fetch: transport });

    const result = await client.ping();

    expect(result).toEqual({ ok: true, livemode: false, apiVersion: '2026-06-16' });
    // GET to /v1/ping with auth, no body.
    const call = transport.calls[0];
    expect(call.method).toBe('GET');
    expect(call.url).toBe(`${BASE}/v1/ping`);
    expect(call.headers['authorization']).toBe('Bearer sk_test_x');
    expect(call.body).toBeUndefined();
  });

  it('maps a live-mode response through', async () => {
    const transport = createTestTransport({
      'GET /v1/ping': () => ({
        status: 200,
        body: { ok: true, livemode: true, api_version: '2026-06-16' },
      }),
    });
    const client = new NexusPay({ apiKey: 'sk_live_x', baseUrl: BASE, fetch: transport });

    const result = await client.ping();
    expect(result.livemode).toBe(true);
    expect(result.apiVersion).toBe('2026-06-16');
  });

  it('works with a plain mock fetch (no createTestTransport)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ ok: true, livemode: false, api_version: '2026-06-16' }),
    });
    const client = new NexusPay({
      apiKey: API_KEY,
      baseUrl: BASE,
      fetch: fetchMock as unknown as typeof fetch,
    });

    const result = await client.ping();
    expect(result.apiVersion).toBe('2026-06-16');
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe(`${BASE}/v1/ping`);
    expect((init as RequestInit).method).toBe('GET');
  });
});
