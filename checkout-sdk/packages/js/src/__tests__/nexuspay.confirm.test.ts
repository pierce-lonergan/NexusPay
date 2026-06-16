import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { NexusPay } from '../nexuspay';
import type { ConfirmResult } from '../types';

/**
 * INT-6: the SDK `confirm()` consumes the gateway's `ConfirmResponse` verbatim (HttpClient does
 * `response.json() as T` with NO snake<->camel transform). These tests pin that the new wire shape
 * — camelCase `status`/`nextAction`/`error` plus the added `paymentId`/`mode`/`livemode`, and the new
 * `processing` status — is read correctly and drives the right events.
 *
 * A `tsc` compile of this file is itself a guard: `ConfirmResult` must accept `processing`,
 * `paymentId`, `mode`, and `livemode`, or the typed assignments below fail to type-check.
 */
describe('NexusPay.confirm (INT-6 ConfirmResult wire shape)', () => {
  const originalFetch = globalThis.fetch;

  /** Build a NexusPay with a pre-loaded session so confirm()'s ensureSession() passes. */
  async function loadedClient(): Promise<NexusPay> {
    globalThis.fetch = vi.fn().mockResolvedValueOnce({
      ok: true,
      json: () =>
        Promise.resolve({
          id: 'ps_1',
          status: 'open',
          amount: 4999,
          currency: 'USD',
          allowedPaymentMethods: ['card'],
        }),
    });
    const np = new NexusPay('pk_test_1', { apiBase: 'https://api.test.com' });
    await np.loadSession('sess_jwt');
    return np;
  }

  /** Stub the NEXT fetch (the confirm POST) to resolve with the given ConfirmResponse body. */
  function mockConfirmBody(body: unknown): void {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve(body),
    });
  }

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  // --- C1: succeeded -> resolves with the new fields + emits payment_complete ---

  it('reads a succeeded result (paymentId/mode/livemode) and emits payment_complete', async () => {
    const np = await loadedClient();
    const onComplete = vi.fn();
    np.on('payment_complete', onComplete);

    mockConfirmBody({ status: 'succeeded', paymentId: 'pay_test_x', mode: 'test', livemode: false });
    const result = await np.confirm('ptok_1');

    expect(result.status).toBe('succeeded');
    expect(result.paymentId).toBe('pay_test_x');
    expect(result.mode).toBe('test');
    expect(result.livemode).toBe(false);
    expect(onComplete).toHaveBeenCalledWith(result);
  });

  // --- C2: requires_action -> carries nextAction (drives useConfirmPayment's ChallengeHandler) ---

  it('reads a requires_action result with a camelCase nextAction (3DS)', async () => {
    const np = await loadedClient();

    mockConfirmBody({
      status: 'requires_action',
      paymentId: 'pay_3ds',
      mode: 'live',
      livemode: true,
      nextAction: { type: 'three_d_secure', url: 'https://3ds.example.com/c' },
    });
    const result = await np.confirm('ptok_1');

    expect(result.status).toBe('requires_action');
    expect(result.nextAction).toEqual({ type: 'three_d_secure', url: 'https://3ds.example.com/c' });
  });

  // --- C2b: processing -> a valid (new) terminal-ish status, no success/failure event ---

  it('reads a processing result without emitting payment_complete or payment_failed', async () => {
    const np = await loadedClient();
    const onComplete = vi.fn();
    const onFailed = vi.fn();
    np.on('payment_complete', onComplete);
    np.on('payment_failed', onFailed);

    // `processing` must be assignable to ConfirmResult.status (type-level INT-6 guard).
    const body: ConfirmResult = { status: 'processing', paymentId: 'pay_proc', mode: 'live', livemode: true };
    mockConfirmBody(body);
    const result = await np.confirm('ptok_1');

    expect(result.status).toBe('processing');
    expect(onComplete).not.toHaveBeenCalled();
    expect(onFailed).not.toHaveBeenCalled();
  });

  // --- C3: failed -> emits payment_failed with the mapped error ---

  it('reads a failed result and emits payment_failed with the error', async () => {
    const np = await loadedClient();
    const onFailed = vi.fn();
    np.on('payment_failed', onFailed);

    mockConfirmBody({
      status: 'failed',
      paymentId: 'pay_fail',
      mode: 'test',
      livemode: false,
      error: { type: 'payment_error', code: 'card_declined', message: 'Your card was declined' },
    });
    const result = await np.confirm('ptok_1');

    expect(result.status).toBe('failed');
    expect(onFailed).toHaveBeenCalledWith({
      type: 'payment_error',
      code: 'card_declined',
      message: 'Your card was declined',
    });
  });
});
