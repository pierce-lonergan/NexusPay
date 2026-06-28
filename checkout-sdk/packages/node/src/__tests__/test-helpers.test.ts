import { describe, it, expect } from 'vitest';
import { createHmac } from 'node:crypto';
import {
  verifyWebhook,
  constructEvent,
  generateTestHeaderString,
  generateTestSignature,
} from '../webhooks';
import { testFixtures, buildTestEvent } from '../fixtures';
import { createTestTransport } from '../testing';
import { NexusPay } from '../client';
import { NexusPayError } from '../errors';
import { WEBHOOK_EVENT_TYPES } from '../types';
import type { WebhookEventType, DisputeWebhookObject } from '../types';

const SECRET = 'whsec_test_secret';

// ---- E1: generateTestHeaderString round-trip ----
describe('E1 generateTestHeaderString / generateTestSignature', () => {
  it('produces a header bag that verifyWebhook accepts (string payload)', () => {
    const body = JSON.stringify({ hello: 'world', n: 1 });
    const bag = generateTestHeaderString({ payload: body, secret: SECRET });

    expect(bag['x-nexuspay-signature']).toMatch(/^[0-9a-f]+$/);
    expect(typeof bag['x-nexuspay-timestamp']).toBe('string');
    expect(bag.body).toBe(body);
    expect(verifyWebhook(body, bag['x-nexuspay-signature'], SECRET)).toBe(true);
  });

  it('single-sources the HMAC (sig equals an independent createHmac reproduction)', () => {
    const body = '{"a":1}';
    const bag = generateTestHeaderString({ payload: body, secret: SECRET });
    const expected = createHmac('sha256', SECRET).update(body).digest('hex');
    expect(bag['x-nexuspay-signature']).toBe(expected);
  });

  it('constructEvent succeeds with the generated header bag', () => {
    const event = buildTestEvent('payment.succeeded');
    const body = JSON.stringify(event);
    const bag = generateTestHeaderString({ payload: body, secret: SECRET });

    const { body: canonical, ...headers } = bag;
    const parsed = constructEvent(canonical, headers, SECRET);
    expect(parsed.type).toBe('payment.succeeded');
  });

  it('the fresh timestamp passes an unsafeHeaderToleranceSeconds window', () => {
    const body = '{"x":true}';
    const bag = generateTestHeaderString({ payload: body, secret: SECRET });
    const { body: canonical, ...headers } = bag;
    expect(() =>
      constructEvent(canonical, headers, SECRET, { unsafeHeaderToleranceSeconds: 300 }),
    ).not.toThrow();
  });

  it('signs an object payload once and returns the canonical body that round-trips', () => {
    const payload = { id: 'evt_test', amount: 1000 };
    const bag = generateTestHeaderString({ payload, secret: SECRET });
    // The canonical body is JSON.stringify(payload) signed once.
    expect(bag.body).toBe(JSON.stringify(payload));
    expect(verifyWebhook(bag.body, bag['x-nexuspay-signature'], SECRET)).toBe(true);
  });

  it('a tampered body fails verification', () => {
    const body = '{"amount":1000}';
    const { body: _canonical, ...headers } = generateTestHeaderString({
      payload: body,
      secret: SECRET,
    });
    const tampered = '{"amount":9999}';
    expect(verifyWebhook(tampered, headers['x-nexuspay-signature'], SECRET)).toBe(false);
    expect(() => constructEvent(tampered, headers, SECRET)).toThrow();
  });

  it('generateTestSignature returns the bare-hex sig for the verifyWebhook path', () => {
    const body = '{"k":"v"}';
    const sig = generateTestSignature(body, SECRET);
    expect(sig).toMatch(/^[0-9a-f]+$/);
    expect(verifyWebhook(body, sig, SECRET)).toBe(true);
  });
});

// ---- E2: typed fixtures ----
describe('E2 testFixtures / buildTestEvent', () => {
  it('has exactly one fixture per WEBHOOK_EVENT_TYPES and vice-versa', () => {
    const keys = Object.keys(testFixtures) as WebhookEventType[];
    expect(keys).toHaveLength(WEBHOOK_EVENT_TYPES.length);
    for (const t of WEBHOOK_EVENT_TYPES) {
      expect(testFixtures[t]).toBeDefined();
    }
    for (const k of keys) {
      expect(WEBHOOK_EVENT_TYPES).toContain(k);
    }
  });

  it('each fixture self-describes: type===key, livemode false, evt_ id, api_version', () => {
    for (const t of WEBHOOK_EVENT_TYPES) {
      const f = testFixtures[t];
      expect(f.type).toBe(t);
      expect(f.livemode).toBe(false);
      expect(f.id).toMatch(/^evt_test_/);
      expect(f.api_version).toBe('2026-06-16');
      expect(typeof f.created).toBe('number');
      expect(f.data.metadata).toBeDefined();
    }
  });

  it('object discriminants match the family', () => {
    expect(testFixtures['payment.succeeded'].data.object.object).toBe('payment');
    expect(testFixtures['payment.refunded'].data.object.object).toBe('refund');
    expect(testFixtures['payment.refund.created'].data.object.object).toBe('refund');

    const disp = testFixtures['dispute.created'].data.object as DisputeWebhookObject;
    expect(disp.object).toBe('dispute');
    expect(disp.dispute_id).toBe('dp_test_123');
  });

  it('every dispute fixture is a dispute object with a dispute_id', () => {
    for (const t of WEBHOOK_EVENT_TYPES) {
      if (t.startsWith('dispute.')) {
        const o = testFixtures[t].data.object as DisputeWebhookObject;
        expect(o.object).toBe('dispute');
        expect(o.dispute_id).toBeTruthy();
      }
    }
  });

  it('buildTestEvent overrides win while keeping type', () => {
    const evt = buildTestEvent('payment.succeeded', { data: { object: { amount: 999 } } });
    expect(evt.type).toBe('payment.succeeded');
    expect(evt.data.object.amount).toBe(999);
    // unchanged fields preserved
    expect(evt.data.object.object).toBe('payment');
    expect(evt.data.object.currency).toBe('USD');
    // fixture not mutated
    expect(testFixtures['payment.succeeded'].data.object.amount).toBe(1000);
  });

  it('buildTestEvent merges top-level + metadata overrides', () => {
    const evt = buildTestEvent('dispute.created', {
      id: 'evt_custom',
      data: { metadata: { case: 'x' } },
    });
    expect(evt.id).toBe('evt_custom');
    expect(evt.data.metadata.case).toBe('x');
  });
});

// ---- E4: createTestTransport ----
const BASE = 'https://api.test.nexuspay.io';
const API_KEY = 'sk_test_x';

describe('E4 createTestTransport', () => {
  it('maps a route, records the call (method/path/headers incl Authorization)', async () => {
    const transport = createTestTransport({
      'GET /v1/ping': () => ({
        status: 200,
        body: { ok: true, livemode: false, api_version: '2026-06-16' },
      }),
    });

    // No global fetch dependency — pass the transport in.
    const client = new NexusPay({ apiKey: API_KEY, baseUrl: BASE, fetch: transport });
    const result = await client.ping();

    expect(result.ok).toBe(true);
    expect(transport.calls).toHaveLength(1);
    const call = transport.calls[0];
    expect(call.method).toBe('GET');
    expect(call.path).toBe('/v1/ping');
    expect(call.url).toBe(`${BASE}/v1/ping`);
    expect(call.headers['authorization']).toBe('Bearer sk_test_x');
    expect(call.body).toBeUndefined();
  });

  it('records a POST body and returns a 201 canned body', async () => {
    const transport = createTestTransport({
      'POST /v1/payments': (req) => ({
        status: 201,
        body: { id: 'pay_test_1', status: 'succeeded', echo: req.body },
      }),
    });
    const client = new NexusPay({ apiKey: API_KEY, baseUrl: BASE, fetch: transport });

    const payment = await client.createPayment({ amount: 1000, currency: 'usd', capture: true });
    expect(payment.id).toBe('pay_test_1');

    const call = transport.calls[0];
    expect(call.method).toBe('POST');
    expect(call.path).toBe('/v1/payments');
    expect((call.body as { amount: number }).amount).toBe(1000);
  });

  it('an unmatched route returns a 404 NexusPayError envelope', async () => {
    const transport = createTestTransport({});
    const client = new NexusPay({ apiKey: API_KEY, baseUrl: BASE, fetch: transport });

    await expect(client.getPayment('pay_x')).rejects.toBeInstanceOf(NexusPayError);
    try {
      await client.getPayment('pay_x');
    } catch (err) {
      expect(err).toBeInstanceOf(NexusPayError);
      expect((err as NexusPayError).status).toBe(404);
      expect((err as NexusPayError).code).toBe('no_test_handler');
    }
  });

  it('onUnmatched: "throw" throws a clear error', async () => {
    const transport = createTestTransport({}, { onUnmatched: 'throw' });
    await expect(transport(`${BASE}/v1/ping`, { method: 'GET' })).rejects.toThrow(
      /no handler for "GET \/v1\/ping"/,
    );
  });
});
