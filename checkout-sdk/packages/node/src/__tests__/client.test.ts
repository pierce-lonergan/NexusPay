import { describe, it, expect, vi } from 'vitest';
import { NexusPay } from '../client';
import { NexusPayError } from '../errors';
import type { NexusPayErrorType } from '../errors';
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

  // TEST-6 (A3): a requires_action Payment exposes next_action.{type,url}; a success has it undefined.
  it('getPayment surfaces next_action for a requires_action payment', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      okJson({
        id: 'pay_1',
        status: 'requires_action',
        amount: 1000,
        currency: 'usd',
        next_action: { type: 'redirect_to_url', url: 'https://test.nexuspay.local/3ds/pay_1' },
      }),
    );
    const client = makeClient(fetchMock);

    const result = await client.getPayment('pay_1');

    expect(result.status).toBe('requires_action');
    expect(result.next_action).toBeDefined();
    expect(result.next_action?.type).toBe('redirect_to_url');
    expect(result.next_action?.url).toBe('https://test.nexuspay.local/3ds/pay_1');
  });

  it('getPayment has no next_action for a success payment', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: 'pay_1', status: 'succeeded', amount: 1000, currency: 'usd' }));
    const client = makeClient(fetchMock);

    const result = await client.getPayment('pay_1');

    expect(result.status).toBe('succeeded');
    expect(result.next_action).toBeUndefined();
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

  // A CALLER-initiated abort (via opts.signal) is an intentional cancellation,
  // NOT a timeout. As of 0.1.1 it rejects with the raw AbortError, distinct from
  // the timeout test above (which still maps to NexusPayError). This documents
  // the deliberate 0.1.0 -> 0.1.1 cancellation-shape change on the default
  // (maxRetries:0) path. See CHANGELOG and README.
  it('rethrows the raw AbortError on a caller-signal abort (not a NexusPayError timeout)', async () => {
    const fetchMock = vi.fn().mockImplementation((_url, init: RequestInit) => {
      return new Promise((_resolve, reject) => {
        init.signal?.addEventListener('abort', () => {
          reject(new DOMException('The operation was aborted', 'AbortError'));
        });
      });
    });
    // Large timeout so the timeout path can't fire — only the caller abort does.
    const client = new NexusPay({
      apiKey: API_KEY,
      baseUrl: BASE,
      timeoutMs: 60_000,
      fetch: fetchMock as unknown as typeof fetch,
    });

    const controller = new AbortController();
    const promise = client.getPayment('pay_1', { signal: controller.signal });
    controller.abort();

    await expect(promise).rejects.toSatisfy((err: unknown) => {
      // Raw AbortError surfaces — NOT remapped to a NexusPayError timeout.
      expect(err).not.toBeInstanceOf(NexusPayError);
      expect((err as { name?: string }).name).toBe('AbortError');
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

  // --- DX-2 critique 4.1: 'api_error' is part of the typed union ---
  it("surfaces a typed 'api_error' (member of NexusPayErrorType) for a non-envelope 5xx", async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ oops: true }, 503));
    const client = makeClient(fetchMock);

    await expect(client.getPayment('pay_1')).rejects.toSatisfy((err: unknown) => {
      const e = err as NexusPayError;
      expect(e).toBeInstanceOf(NexusPayError);
      // Assignable to the closed union member, not just `| string`.
      const t: NexusPayErrorType = e.type as NexusPayErrorType;
      expect(t).toBe('api_error');
      expect(e.status).toBe(503);
      return true;
    });
  });

  // --- DX-2 critique 4.3: createRefund guards the discriminant ---
  describe('createRefund discriminant guard', () => {
    it("throws an 'api_error' when the 2xx body lacks a boolean requires_approval", async () => {
      // A proxied / drifted 2xx with no discriminant — must NOT be treated as a
      // requires_approval:false Refund.
      const fetchMock = vi.fn().mockResolvedValue(
        okJson({ id: 'ref_1', payment_id: 'pay_1', status: 'succeeded', amount: 1000 }, 201),
      );
      const client = makeClient(fetchMock);

      await expect(client.createRefund('pay_1', { amount: 1000 })).rejects.toSatisfy(
        (err: unknown) => {
          const e = err as NexusPayError;
          expect(e).toBeInstanceOf(NexusPayError);
          expect(e.type).toBe('api_error');
          expect(e.code).toBe('unexpected_refund_response');
          return true;
        },
      );
    });

    it('still returns on a valid 201 Refund (requires_approval:false) — happy path unchanged', async () => {
      const fetchMock = vi.fn().mockResolvedValue(
        okJson(
          { id: 'ref_1', payment_id: 'pay_1', status: 'succeeded', amount: 1000, requires_approval: false },
          201,
        ),
      );
      const client = makeClient(fetchMock);
      const result = await client.createRefund('pay_1', { amount: 1000 });
      expect(result.requires_approval).toBe(false);
      if (!result.requires_approval) {
        const refund: Refund = result;
        expect(refund.id).toBe('ref_1');
      }
    });

    it('still returns on a valid 202 RefundApproval (requires_approval:true) — happy path unchanged', async () => {
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
      }
    });
  });

  // --- DX-2 critique 4.4: single capture surface ---
  describe('createPayment capture/captureMethod reconciliation', () => {
    it('throws BEFORE the request when capture:true conflicts with captureMethod:manual', async () => {
      const fetchMock = vi.fn();
      const client = makeClient(fetchMock);
      await expect(
        client.createPayment({ amount: 100, currency: 'usd', capture: true, captureMethod: 'manual' }),
      ).rejects.toThrow(/conflicting capture/i);
      // No request was sent — the guard is client-side, no silent server precedence.
      expect(fetchMock).not.toHaveBeenCalled();
    });

    it('throws when capture:false conflicts with captureMethod:automatic', async () => {
      const fetchMock = vi.fn();
      const client = makeClient(fetchMock);
      await expect(
        client.createPayment({ amount: 100, currency: 'usd', capture: false, captureMethod: 'automatic' }),
      ).rejects.toThrow(/conflicting capture/i);
      expect(fetchMock).not.toHaveBeenCalled();
    });

    it('proceeds and forwards both when they AGREE (capture:true + captureMethod:automatic)', async () => {
      const fetchMock = vi.fn().mockResolvedValue(okJson({ id: 'pay_1', status: 'x' }));
      const client = makeClient(fetchMock);
      await client.createPayment({ amount: 100, currency: 'usd', capture: true, captureMethod: 'automatic' });
      expect(fetchMock).toHaveBeenCalledOnce();
      const body = JSON.parse(lastCall(fetchMock).init.body as string);
      expect(body.capture).toBe(true);
      expect(body.capture_method).toBe('automatic');
    });

    it('still sends only `capture` when only capture is set (back-compat wire shape)', async () => {
      const fetchMock = vi.fn().mockResolvedValue(okJson({ id: 'pay_1', status: 'x' }));
      const client = makeClient(fetchMock);
      await client.createPayment({ amount: 100, currency: 'usd', capture: true });
      const body = JSON.parse(lastCall(fetchMock).init.body as string);
      expect(body.capture).toBe(true);
      expect(body.capture_method).toBeUndefined();
    });
  });

  // --- mandates (TEST-3d) ---
  describe('mandates', () => {
    it('createMandate POSTs /v1/mandates with snake_case payment_method', async () => {
      const fetchMock = vi.fn().mockResolvedValue(
        okJson({ id: 'mandate_1', object: 'mandate', status: 'ACTIVE' }, 201),
      );
      const client = makeClient(fetchMock);

      const result = await client.createMandate({ paymentMethod: 'pm_1', type: 'MULTI_USE' });

      const { url, init } = lastCall(fetchMock);
      expect(url).toBe(`${BASE}/v1/mandates`);
      expect(init.method).toBe('POST');
      const body = JSON.parse(init.body as string);
      expect(body.payment_method).toBe('pm_1');
      expect(body.type).toBe('MULTI_USE');
      expect(result.id).toBe('mandate_1');
      expect(result.object).toBe('mandate');
    });

    it('getMandate GETs the id path with no body', async () => {
      const fetchMock = vi.fn().mockResolvedValue(
        okJson({ id: 'mandate_1', object: 'mandate', status: 'ACTIVE' }),
      );
      const client = makeClient(fetchMock);

      const result = await client.getMandate('mandate_1');

      const { url, init } = lastCall(fetchMock);
      expect(url).toBe(`${BASE}/v1/mandates/mandate_1`);
      expect(init.method).toBe('GET');
      expect(init.body).toBeUndefined();
      expect(result.id).toBe('mandate_1');
    });

    it('listMandates GETs /v1/mandates with limit/offset', async () => {
      const fetchMock = vi.fn().mockResolvedValue(okJson([{ id: 'mandate_1', object: 'mandate' }]));
      const client = makeClient(fetchMock);

      await client.listMandates({ limit: 5, offset: 10 });

      const { url, init } = lastCall(fetchMock);
      expect(url).toBe(`${BASE}/v1/mandates?limit=5&offset=10`);
      expect(init.method).toBe('GET');
    });

    it('revokeMandate POSTs /v1/mandates/{id}/revoke and returns the INACTIVE body', async () => {
      const fetchMock = vi.fn().mockResolvedValue(
        okJson({ id: 'mandate_1', object: 'mandate', status: 'INACTIVE' }),
      );
      const client = makeClient(fetchMock);

      const result = await client.revokeMandate('mandate_1');

      const { url, init } = lastCall(fetchMock);
      expect(url).toBe(`${BASE}/v1/mandates/mandate_1/revoke`);
      expect(init.method).toBe('POST');
      expect(result.status).toBe('INACTIVE');
    });
  });

  // --- DX-2 critique 4.2: opt-in retry/backoff + auto-idempotency ---
  describe('opt-in retry / backoff / idempotency', () => {
    function okJsonWithHeaders(body: unknown, status: number, headers: Record<string, string> = {}) {
      return {
        ok: status >= 200 && status < 300,
        status,
        statusText: 'X',
        headers: new Headers(headers),
        json: () => Promise.resolve(body),
      };
    }

    function makeRetryClient(fetchMock: ReturnType<typeof vi.fn>, extra: Record<string, unknown> = {}) {
      return new NexusPay({
        apiKey: API_KEY,
        baseUrl: BASE,
        fetch: fetchMock as unknown as typeof fetch,
        // Deterministic seams: no real sleeping, fixed key.
        sleep: vi.fn().mockResolvedValue(undefined) as unknown as (ms: number) => Promise<void>,
        randomUUID: () => 'auto-idem-key',
        ...extra,
      });
    }

    it('maxRetries:0 does EXACTLY ONE fetch and no auto idempotency key (byte-identical)', async () => {
      const fetchMock = vi.fn().mockResolvedValue(okJson({ id: 'pay_1', status: 'x' }));
      const client = makeRetryClient(fetchMock); // default maxRetries 0
      await client.createPayment({ amount: 1, currency: 'usd' });
      expect(fetchMock).toHaveBeenCalledOnce();
      expect((lastCall(fetchMock).init.headers as Record<string, string>)['Idempotency-Key']).toBeUndefined();
    });

    it('retries a 429 then succeeds, honoring Retry-After and reusing ONE idempotency key', async () => {
      const sleep = vi.fn().mockResolvedValue(undefined);
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(okJsonWithHeaders({ error: { type: 'rate_limit_error', code: 'rl', message: 'slow down' } }, 429, { 'Retry-After': '2' }))
        .mockResolvedValueOnce(okJson({ id: 'pay_1', status: 'x' }));
      const client = makeRetryClient(fetchMock, { sleep: sleep as unknown as (ms: number) => Promise<void> });

      const result = await client.createPayment({ amount: 1, currency: 'usd' }, { maxRetries: 3 });
      expect((result as { id: string }).id).toBe('pay_1');
      expect(fetchMock).toHaveBeenCalledTimes(2);
      // Retry-After: 2s honored as the backoff delay.
      expect(sleep).toHaveBeenCalledWith(2000);

      // Same auto-generated Idempotency-Key on BOTH attempts.
      const key0 = (fetchMock.mock.calls[0][1] as RequestInit).headers as Record<string, string>;
      const key1 = (fetchMock.mock.calls[1][1] as RequestInit).headers as Record<string, string>;
      expect(key0['Idempotency-Key']).toBe('auto-idem-key');
      expect(key1['Idempotency-Key']).toBe('auto-idem-key');
    });

    it('reuses a CALLER-supplied idempotency key across the retry sequence (no auto-gen override)', async () => {
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(okJsonWithHeaders({}, 500))
        .mockResolvedValueOnce(okJson({ id: 'pay_1', status: 'x' }));
      const client = makeRetryClient(fetchMock);

      await client.createPayment({ amount: 1, currency: 'usd' }, { maxRetries: 2, idempotencyKey: 'caller_key' });
      expect(fetchMock).toHaveBeenCalledTimes(2);
      for (const call of fetchMock.mock.calls) {
        const h = (call[1] as RequestInit).headers as Record<string, string>;
        expect(h['Idempotency-Key']).toBe('caller_key');
      }
    });

    it('does NOT retry a non-429 4xx (e.g. 402), throws after one fetch', async () => {
      const fetchMock = vi.fn().mockResolvedValue(
        okJson({ error: { type: 'payment_error', code: 'card_declined', message: 'no' } }, 402),
      );
      const client = makeRetryClient(fetchMock);
      await expect(
        client.createPayment({ amount: 1, currency: 'usd' }, { maxRetries: 5 }),
      ).rejects.toBeInstanceOf(NexusPayError);
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    it('exhausts retries on persistent 5xx and rethrows the last error', async () => {
      const fetchMock = vi.fn().mockResolvedValue(okJson({}, 503));
      const client = makeRetryClient(fetchMock);
      await expect(
        client.createPayment({ amount: 1, currency: 'usd' }, { maxRetries: 2 }),
      ).rejects.toSatisfy((err: unknown) => {
        expect((err as NexusPayError).status).toBe(503);
        return true;
      });
      // initial try + 2 retries = 3 fetches.
      expect(fetchMock).toHaveBeenCalledTimes(3);
    });

    it('retries a network (TypeError) failure then succeeds', async () => {
      const fetchMock = vi
        .fn()
        .mockRejectedValueOnce(new TypeError('network down'))
        .mockResolvedValueOnce(okJson({ id: 'pay_1', status: 'x' }));
      const client = makeRetryClient(fetchMock);
      const result = await client.createPayment({ amount: 1, currency: 'usd' }, { maxRetries: 1 });
      expect((result as { id: string }).id).toBe('pay_1');
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });
  });
});
