import { describe, it, expect, vi } from 'vitest';
import { NexusPay } from '../client';
import { NexusPayError } from '../errors';
import type { RefundApproval, Refund } from '../types';

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

describe('NexusPay client', () => {
  it('createPayment POSTs to /v1/payments with auth + content-type and passes capture alias', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: 'pay_1', status: 'requires_confirmation' }));
    const client = makeClient(fetchMock);

    await client.createPayment({ amount: 1000, currency: 'usd', capture: true });

    const { url, init } = lastCall(fetchMock);
    expect(url).toBe(`${BASE}/v1/payments`);
    expect(init.method).toBe('POST');
    const headers = init.headers as Record<string, string>;
    expect(headers['Authorization']).toBe('Bearer sk_test_x');
    expect(headers['Content-Type']).toBe('application/json');
    const body = JSON.parse(init.body as string);
    expect(body.capture).toBe(true);
    expect(body.amount).toBe(1000);
    expect(body.currency).toBe('usd');
  });

  it('createPayment maps captureMethod -> capture_method', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: 'pay_1', status: 'x' }));
    const client = makeClient(fetchMock);

    await client.createPayment({ amount: 500, currency: 'eur', captureMethod: 'manual' });

    const body = JSON.parse(lastCall(fetchMock).init.body as string);
    expect(body.capture_method).toBe('manual');
    expect(body.capture).toBeUndefined();
  });

  it('includes Idempotency-Key only when supplied', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: 'pay_1', status: 'x' }));
    const client = makeClient(fetchMock);

    await client.createPayment({ amount: 1, currency: 'usd' });
    expect((lastCall(fetchMock).init.headers as Record<string, string>)['Idempotency-Key']).toBeUndefined();

    await client.createPayment({ amount: 1, currency: 'usd' }, { idempotencyKey: 'idem_42' });
    expect((lastCall(fetchMock).init.headers as Record<string, string>)['Idempotency-Key']).toBe('idem_42');
  });

  it('getPayment GETs the id path with no body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: 'pay_1', status: 'succeeded' }));
    const client = makeClient(fetchMock);

    const result = await client.getPayment('pay_1');

    const { url, init } = lastCall(fetchMock);
    expect(url).toBe(`${BASE}/v1/payments/pay_1`);
    expect(init.method).toBe('GET');
    expect(init.body).toBeUndefined();
    expect(result.id).toBe('pay_1');
  });

  it('capturePayment maps amount_to_capture', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: 'pay_1', status: 'succeeded' }));
    const client = makeClient(fetchMock);

    await client.capturePayment('pay_1', { amountToCapture: 750 });

    const { url, init } = lastCall(fetchMock);
    expect(url).toBe(`${BASE}/v1/payments/pay_1/capture`);
    expect(JSON.parse(init.body as string).amount_to_capture).toBe(750);
  });

  it('cancelPayment maps cancellation_reason', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: 'pay_1', status: 'canceled' }));
    const client = makeClient(fetchMock);

    await client.cancelPayment('pay_1', { cancellationReason: 'duplicate' });

    expect(JSON.parse(lastCall(fetchMock).init.body as string).cancellation_reason).toBe('duplicate');
  });

  it('confirmPayment maps payment_method_type / return_url', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: 'pay_1', status: 'succeeded' }));
    const client = makeClient(fetchMock);

    await client.confirmPayment('pay_1', { paymentMethodType: 'card', returnUrl: 'https://x/y' });

    const { url, init } = lastCall(fetchMock);
    expect(url).toBe(`${BASE}/v1/payments/pay_1/confirm`);
    const body = JSON.parse(init.body as string);
    expect(body.payment_method_type).toBe('card');
    expect(body.return_url).toBe('https://x/y');
  });

  it('maps an INT-2 error envelope into NexusPayError', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      okJson(
        {
          error: {
            type: 'payment_error',
            code: 'card_declined',
            message: 'Declined',
            request_id: 'req_123',
          },
        },
        402,
      ),
    );
    const client = makeClient(fetchMock);

    await expect(client.createPayment({ amount: 1, currency: 'usd' })).rejects.toSatisfy((err: unknown) => {
      expect(err).toBeInstanceOf(NexusPayError);
      const e = err as NexusPayError;
      expect(e.type).toBe('payment_error');
      expect(e.code).toBe('card_declined');
      expect(e.requestId).toBe('req_123');
      expect(e.status).toBe(402);
      return true;
    });
  });

  it('falls back to a generic NexusPayError for a non-envelope body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({}, 500));
    const client = makeClient(fetchMock);

    await expect(client.getPayment('pay_1')).rejects.toSatisfy((err: unknown) => {
      const e = err as NexusPayError;
      expect(e).toBeInstanceOf(NexusPayError);
      expect(e.type).toBe('api_error');
      expect(e.status).toBe(500);
      return true;
    });
  });

  it('maps a hanging/aborted request to a timeout NexusPayError', async () => {
    const fetchMock = vi.fn().mockImplementation((_url, init: RequestInit) => {
      return new Promise((_resolve, reject) => {
        init.signal?.addEventListener('abort', () => {
          reject(new DOMException('The operation was aborted', 'AbortError'));
        });
      });
    });
    const client = new NexusPay({
      apiKey: API_KEY,
      baseUrl: BASE,
      timeoutMs: 20,
      fetch: fetchMock as unknown as typeof fetch,
    });

    await expect(client.getPayment('pay_1')).rejects.toSatisfy((err: unknown) => {
      const e = err as NexusPayError;
      expect(e).toBeInstanceOf(NexusPayError);
      expect(e.type).toBe('network_error');
      expect(e.code).toBe('timeout');
      return true;
    });
  });

  it('createRefund resolves a 202 ApprovalResponse (requires_approval narrows)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      okJson(
        {
          id: 'appr_1',
          action: 'refund',
          resource_type: 'payment',
          resource_id: 'pay_1',
          status: 'pending',
          requires_approval: true,
          approval_threshold: 50000,
        },
        202,
      ),
    );
    const client = makeClient(fetchMock);

    const result = await client.createRefund('pay_1', { amount: 60000 });
    expect(result.requires_approval).toBe(true);
    if (result.requires_approval) {
      const approval: RefundApproval = result;
      expect(approval.approval_threshold).toBe(50000);
      expect(approval.id).toBe('appr_1');
    }
    expect(lastCall(fetchMock).url).toBe(`${BASE}/v1/payments/pay_1/refunds`);
  });

  it('createRefund resolves a 201 Refund (requires_approval:false)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      okJson(
        {
          id: 'ref_1',
          payment_id: 'pay_1',
          status: 'succeeded',
          amount: 1000,
          requires_approval: false,
        },
        201,
      ),
    );
    const client = makeClient(fetchMock);

    const result = await client.createRefund('pay_1', { amount: 1000 });
    expect(result.requires_approval).toBe(false);
    if (!result.requires_approval) {
      const refund: Refund = result;
      expect(refund.id).toBe('ref_1');
      expect(refund.amount).toBe(1000);
    }
  });

  it('never leaks the apiKey in a thrown error message or stack', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      okJson(
        { error: { type: 'unauthorized', code: 'bad_key', message: 'nope', request_id: 'req_x' } },
        401,
      ),
    );
    const client = makeClient(fetchMock);

    try {
      await client.getPayment('pay_1');
      expect.unreachable('should have thrown');
    } catch (err) {
      const e = err as NexusPayError;
      const serialized = `${e.message}\n${JSON.stringify(e)}\n${e.stack ?? ''}`;
      expect(serialized).not.toContain(API_KEY);
    }
  });

  it('does not expose the apiKey on the client instance (non-enumerable)', () => {
    const client = makeClient(vi.fn());
    expect(JSON.stringify(client)).not.toContain(API_KEY);
    expect(Object.keys(client)).not.toContain('_apiKey');
  });
});
