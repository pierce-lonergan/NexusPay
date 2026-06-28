import { describe, it, expect, vi } from 'vitest';
import { NexusPay } from '../client';

const BASE = 'https://api.test.nexuspay.io';
const API_KEY = 'sk_test_x';

function okJson(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: 'OK',
    json: () => Promise.resolve(body),
  };
}

function makeClient(fetchMock: ReturnType<typeof vi.fn>) {
  return new NexusPay({ apiKey: API_KEY, baseUrl: BASE, fetch: fetchMock as unknown as typeof fetch });
}

function lastCall(fetchMock: ReturnType<typeof vi.fn>) {
  const call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1];
  return { url: call[0] as string, init: call[1] as RequestInit };
}

describe('TEST-4a test events + delivery visibility', () => {
  it('triggerTestEvent POSTs snake_case body to /v1/test/events and parses TestEvent', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      okJson({ id: 'evt_1', type: 'payment.succeeded', livemode: false, object: { payment_id: 'pay_test_1' } }, 202),
    );
    const client = makeClient(fetchMock);

    const result = await client.triggerTestEvent({
      type: 'payment.succeeded',
      id: 'pay_test_1',
      data: { amount: 4242 },
    });

    const { url, init } = lastCall(fetchMock);
    expect(url).toBe(`${BASE}/v1/test/events`);
    expect(init.method).toBe('POST');
    const body = JSON.parse(init.body as string);
    expect(body.type).toBe('payment.succeeded');
    expect(body.id).toBe('pay_test_1');
    expect(body.data).toEqual({ amount: 4242 });

    expect(result.id).toBe('evt_1');
    expect(result.livemode).toBe(false);
    expect(result.object).toEqual({ payment_id: 'pay_test_1' });
  });

  it('triggerTestEvent omits undefined optional fields (compact)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      okJson({ id: 'evt_2', type: 'dispute.created', livemode: false, object: {} }, 202),
    );
    const client = makeClient(fetchMock);

    await client.triggerTestEvent({ type: 'dispute.created' });

    const body = JSON.parse(lastCall(fetchMock).init.body as string);
    expect(body.type).toBe('dispute.created');
    expect('id' in body).toBe(false);
    expect('data' in body).toBe(false);
  });

  it('getWebhookDeliveryBody GETs the /body path and parses canonical_body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      okJson({
        id: 'whd_1',
        endpoint_id: 'we_1',
        event_id: 'evt_1',
        event_type: 'payment.succeeded',
        canonical_body: '{"id":"evt_1"}',
      }),
    );
    const client = makeClient(fetchMock);

    const result = await client.getWebhookDeliveryBody('whd_1');

    const { url, init } = lastCall(fetchMock);
    expect(url).toBe(`${BASE}/v1/webhook-deliveries/whd_1/body`);
    expect(init.method).toBe('GET');
    expect(init.body).toBeUndefined();
    expect(result.canonical_body).toBe('{"id":"evt_1"}');
  });

  it('getWebhookDeliverySignature GETs the /signature path; result carries no secret field', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      okJson({
        id: 'whd_1',
        endpoint_id: 'we_1',
        algorithm: 'HmacSHA256',
        signature: 'deadbeef',
        rotated_secret_caveat: 'recomputed with current secret',
      }),
    );
    const client = makeClient(fetchMock);

    const result = await client.getWebhookDeliverySignature('whd_1');

    const { url, init } = lastCall(fetchMock);
    expect(url).toBe(`${BASE}/v1/webhook-deliveries/whd_1/signature`);
    expect(init.method).toBe('GET');
    expect(result.algorithm).toBe('HmacSHA256');
    expect(result.signature).toBe('deadbeef');
    // The signature result type carries no secret field.
    expect('secret' in result).toBe(false);
  });

  it('never serializes the apiKey into the request body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      okJson({ id: 'evt_3', type: 'payment.succeeded', livemode: false, object: {} }, 202),
    );
    const client = makeClient(fetchMock);

    await client.triggerTestEvent({ type: 'payment.succeeded' });

    const { init } = lastCall(fetchMock);
    // The key rides in the Authorization header only — never the body.
    expect(init.body as string).not.toContain(API_KEY);
    const headers = init.headers as Record<string, string>;
    expect(headers['Authorization']).toBe(`Bearer ${API_KEY}`);
  });
});
