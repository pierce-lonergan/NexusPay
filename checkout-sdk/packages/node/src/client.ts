/**
 * NexusPay server SDK client.
 *
 * Zero runtime dependencies — uses the global `fetch` / `AbortController`
 * (Node 18+). camelCase params are mapped explicitly to the snake_case wire
 * body per method so the request payload matches the gateway-api contract.
 *
 * Secret hygiene: the apiKey is held in a non-enumerable field and is never
 * logged or interpolated into error messages.
 */

import { NexusPayError } from './errors';
import type {
  CancelPaymentParams,
  CapturePaymentParams,
  ConfirmPaymentParams,
  CreatePaymentParams,
  CreatePaymentSessionParams,
  CreateRefundParams,
  CreateRefundResult,
  Payment,
  PaymentSession,
  RequestOptions,
} from './types';

export interface NexusPayOptions {
  /** Secret API key ("sk_..."). Never logged. */
  apiKey: string;
  /** API base URL, e.g. https://api.nexuspay.io */
  baseUrl: string;
  /** Default request timeout in ms (default 30_000). */
  timeoutMs?: number;
  /** Injectable fetch (defaults to the global fetch). */
  fetch?: typeof fetch;
}

const DEFAULT_TIMEOUT = 30_000;

/** Drops undefined values so they never reach the wire body. */
function compact(obj: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(obj)) {
    if (v !== undefined) out[k] = v;
  }
  return out;
}

export class NexusPay {
  private readonly baseUrl: string;
  private readonly timeoutMs: number;
  private readonly fetchImpl: typeof fetch;

  constructor(options: NexusPayOptions) {
    if (!options.apiKey) throw new Error('NexusPay: apiKey is required');
    if (!options.baseUrl) throw new Error('NexusPay: baseUrl is required');

    this.baseUrl = options.baseUrl.replace(/\/$/, '');
    this.timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT;
    this.fetchImpl = options.fetch ?? globalThis.fetch;

    // Hold the key in a non-enumerable private field so it does not appear
    // in JSON.stringify / console output of a client instance.
    Object.defineProperty(this, '_apiKey', {
      value: options.apiKey,
      enumerable: false,
      writable: false,
      configurable: false,
    });
  }

  createPaymentSession(
    params: CreatePaymentSessionParams,
    opts?: RequestOptions,
  ): Promise<PaymentSession> {
    const body = compact({
      amount: params.amount,
      currency: params.currency,
      customer_id: params.customerId,
      success_url: params.successUrl,
      cancel_url: params.cancelUrl,
      allowed_payment_methods: params.allowedPaymentMethods,
      branding: params.branding,
      metadata: params.metadata,
    });
    return this.request<PaymentSession>('POST', '/v1/payment-sessions', body, opts);
  }

  createPayment(params: CreatePaymentParams, opts?: RequestOptions): Promise<Payment> {
    const body = compact({
      amount: params.amount,
      currency: params.currency,
      customer_id: params.customerId,
      payment_method_type: params.paymentMethodType,
      payment_method_data: params.paymentMethodData,
      return_url: params.returnUrl,
      description: params.description,
      capture_method: params.captureMethod,
      capture: params.capture,
      metadata: params.metadata,
    });
    return this.request<Payment>('POST', '/v1/payments', body, opts);
  }

  getPayment(id: string, opts?: RequestOptions): Promise<Payment> {
    return this.request<Payment>('GET', `/v1/payments/${encodeURIComponent(id)}`, undefined, opts);
  }

  confirmPayment(
    id: string,
    params?: ConfirmPaymentParams,
    opts?: RequestOptions,
  ): Promise<Payment> {
    const body = compact({
      payment_method_type: params?.paymentMethodType,
      payment_method_data: params?.paymentMethodData,
      return_url: params?.returnUrl,
    });
    return this.request<Payment>(
      'POST',
      `/v1/payments/${encodeURIComponent(id)}/confirm`,
      body,
      opts,
    );
  }

  capturePayment(
    id: string,
    params?: CapturePaymentParams,
    opts?: RequestOptions,
  ): Promise<Payment> {
    const body = compact({ amount_to_capture: params?.amountToCapture });
    return this.request<Payment>(
      'POST',
      `/v1/payments/${encodeURIComponent(id)}/capture`,
      body,
      opts,
    );
  }

  cancelPayment(
    id: string,
    params?: CancelPaymentParams,
    opts?: RequestOptions,
  ): Promise<Payment> {
    const body = compact({ cancellation_reason: params?.cancellationReason });
    return this.request<Payment>(
      'POST',
      `/v1/payments/${encodeURIComponent(id)}/cancel`,
      body,
      opts,
    );
  }

  /**
   * Creates a refund. The gateway returns 201 (`Refund`, requires_approval:false)
   * or 202 (`RefundApproval`, requires_approval:true). Both are 2xx; the caller
   * narrows on `result.requires_approval`.
   */
  createRefund(
    paymentId: string,
    params: CreateRefundParams,
    opts?: RequestOptions,
  ): Promise<CreateRefundResult> {
    const body = compact({
      amount: params.amount,
      currency: params.currency,
      reason: params.reason,
    });
    return this.request<CreateRefundResult>(
      'POST',
      `/v1/payments/${encodeURIComponent(paymentId)}/refunds`,
      body,
      opts,
    );
  }

  private async request<T>(
    method: string,
    path: string,
    body?: Record<string, unknown>,
    opts?: RequestOptions,
  ): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.getApiKey()}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    };
    if (opts?.idempotencyKey) {
      headers['Idempotency-Key'] = opts.idempotencyKey;
    }

    const controller = new AbortController();
    const timeout = opts?.timeoutMs ?? this.timeoutMs;
    const timeoutId = setTimeout(() => controller.abort(), timeout);

    // Link an externally-provided signal: abort ours when the caller's fires.
    let onExternalAbort: (() => void) | undefined;
    if (opts?.signal) {
      if (opts.signal.aborted) {
        controller.abort();
      } else {
        onExternalAbort = () => controller.abort();
        opts.signal.addEventListener('abort', onExternalAbort);
      }
    }

    const init: RequestInit = {
      method,
      headers,
      signal: controller.signal,
    };
    if (body !== undefined && method !== 'GET') {
      init.body = JSON.stringify(body);
    }

    try {
      const res = await this.fetchImpl(url, init);

      if (!res.ok) {
        const raw = await res.json().catch(() => undefined);
        throw NexusPayError.fromEnvelope(raw, res.status);
      }

      return (await res.json()) as T;
    } catch (err) {
      if (isAbortError(err)) {
        throw new NexusPayError({
          type: 'network_error',
          code: 'timeout',
          message: 'Request timed out',
        });
      }
      throw err;
    } finally {
      clearTimeout(timeoutId);
      if (opts?.signal && onExternalAbort) {
        opts.signal.removeEventListener('abort', onExternalAbort);
      }
    }
  }

  private getApiKey(): string {
    return (this as unknown as { _apiKey: string })._apiKey;
  }
}

function isAbortError(err: unknown): boolean {
  return (
    typeof err === 'object' &&
    err !== null &&
    'name' in err &&
    (err as { name: unknown }).name === 'AbortError'
  );
}
