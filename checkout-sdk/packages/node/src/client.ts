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

import { randomUUID } from 'node:crypto';
import { NexusPayError } from './errors';
import type {
  CancelPaymentParams,
  CaptureMethod,
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
  /**
   * Default opt-in retry count for transient failures (429, 5xx, network),
   * applied to every request unless overridden per-call via
   * {@link RequestOptions.maxRetries}. Default 0 = historical behavior: exactly
   * one fetch and no auto-generated idempotency key. See
   * {@link RequestOptions.maxRetries} for the full retry/backoff/idempotency
   * semantics.
   */
  maxRetries?: number;
  /**
   * Injectable delay (ms) used between retry attempts. Defaults to a real
   * `setTimeout`-based sleep; inject a no-op/recording fn in tests to keep
   * backoff deterministic without real time passing.
   */
  sleep?: (ms: number) => Promise<void>;
  /**
   * Injectable idempotency-key generator for the auto-generated retry key.
   * Defaults to `crypto.randomUUID`. Inject in tests for determinism.
   */
  randomUUID?: () => string;
}

const DEFAULT_TIMEOUT = 30_000;
/** Base backoff (ms) for exponential retry: attempt n waits ~ BASE * 2^(n-1). */
const RETRY_BASE_DELAY_MS = 250;
/** Cap on a single backoff wait (ms) when no Retry-After is present. */
const RETRY_MAX_DELAY_MS = 8_000;

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

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
  private readonly defaultMaxRetries: number;
  private readonly sleep: (ms: number) => Promise<void>;
  private readonly randomUUID: () => string;

  constructor(options: NexusPayOptions) {
    if (!options.apiKey) throw new Error('NexusPay: apiKey is required');
    if (!options.baseUrl) throw new Error('NexusPay: baseUrl is required');

    this.baseUrl = options.baseUrl.replace(/\/$/, '');
    this.timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT;
    this.fetchImpl = options.fetch ?? globalThis.fetch;
    this.defaultMaxRetries = options.maxRetries ?? 0;
    this.sleep = options.sleep ?? defaultSleep;
    this.randomUUID = options.randomUUID ?? (() => randomUUID());

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

  // `async` so the client-side capture-conflict guard surfaces as a REJECTED
  // promise (consistent with every other failure path) rather than a synchronous
  // throw; the public signature Promise<Payment> is unchanged.
  async createPayment(params: CreatePaymentParams, opts?: RequestOptions): Promise<Payment> {
    // Single capture surface: `captureMethod` is authoritative, `capture` is the
    // INT-2 boolean alias (true -> automatic, false -> manual). If BOTH are set
    // and they CONFLICT, fail loudly client-side rather than relying on silent
    // server precedence. If only one is set, or both agree, send the resolved
    // single value (we still forward both fields when they agree, for back-compat).
    const resolved = resolveCapture(params.capture, params.captureMethod);

    const body = compact({
      amount: params.amount,
      currency: params.currency,
      customer_id: params.customerId,
      payment_method_type: params.paymentMethodType,
      payment_method_data: params.paymentMethodData,
      return_url: params.returnUrl,
      description: params.description,
      capture_method: resolved.captureMethod,
      capture: resolved.capture,
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
  async createRefund(
    paymentId: string,
    params: CreateRefundParams,
    opts?: RequestOptions,
  ): Promise<CreateRefundResult> {
    const body = compact({
      amount: params.amount,
      currency: params.currency,
      reason: params.reason,
    });
    const result = await this.request<CreateRefundResult>(
      'POST',
      `/v1/payments/${encodeURIComponent(paymentId)}/refunds`,
      body,
      opts,
    );

    // Guard the discriminant BEFORE callers narrow on it. request() does an
    // unchecked `as T` cast, so a 2xx whose body lacks a boolean
    // `requires_approval` (envelope drift, a proxied/edge 2xx, a wrong endpoint
    // behind the same base URL) would silently fall into the
    // `requires_approval === false` (Refund) branch. Fail loudly instead of
    // mis-typing the result.
    if (
      typeof result !== 'object' ||
      result === null ||
      typeof (result as { requires_approval?: unknown }).requires_approval !== 'boolean'
    ) {
      throw new NexusPayError({
        type: 'api_error',
        code: 'unexpected_refund_response',
        message:
          'createRefund: response body did not include a boolean `requires_approval` ' +
          'discriminant (expected a 201 Refund or 202 RefundApproval). Refusing to ' +
          'guess the refund outcome.',
      });
    }
    return result;
  }

  private async request<T>(
    method: string,
    path: string,
    body?: Record<string, unknown>,
    opts?: RequestOptions,
  ): Promise<T> {
    const maxRetries = Math.max(0, opts?.maxRetries ?? this.defaultMaxRetries);

    // maxRetries === 0: the historical default path. No auto-generated
    // idempotency key, exactly one fetch. The success path and the
    // server-error/timeout error shapes are unchanged from 0.1.0. The ONE
    // intentional behavioral change vs 0.1.0: a caller-supplied `opts.signal`
    // abort (an explicit cancellation, distinct from a timeout) now rejects with
    // the raw AbortError instead of being mis-mapped to
    // NexusPayError(network_error/timeout). A timeout still maps to that
    // NexusPayError. See requestOnce() and the CHANGELOG note for 0.1.1.
    if (maxRetries === 0) {
      return this.requestOnce<T>(method, path, body, opts?.idempotencyKey, opts);
    }

    // Retry sequence: pin a SINGLE Idempotency-Key for all attempts so a retried
    // POST is safe by construction. Honor a caller-supplied key; otherwise
    // auto-generate one for mutating requests. GETs are naturally idempotent, so
    // we leave their header untouched (matching the no-retry behavior).
    const isMutating = method !== 'GET';
    let idempotencyKey = opts?.idempotencyKey;
    if (idempotencyKey === undefined && isMutating) {
      idempotencyKey = this.randomUUID();
    }

    let attempt = 0;
    // Total attempts = maxRetries + 1 (the initial try plus the retries).
    for (;;) {
      try {
        return await this.requestOnce<T>(method, path, body, idempotencyKey, opts);
      } catch (err) {
        const retryAfterMs = retryableDelayMs(err, attempt);
        if (retryAfterMs === undefined || attempt >= maxRetries) {
          throw err;
        }
        attempt += 1;
        await this.sleep(retryAfterMs);
      }
    }
  }

  /** A single HTTP attempt. Maps a timeout abort to a network_error. */
  private async requestOnce<T>(
    method: string,
    path: string,
    body: Record<string, unknown> | undefined,
    idempotencyKey: string | undefined,
    opts?: RequestOptions,
  ): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.getApiKey()}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    };
    if (idempotencyKey) {
      headers['Idempotency-Key'] = idempotencyKey;
    }

    const controller = new AbortController();
    const timeout = opts?.timeoutMs ?? this.timeoutMs;
    let timedOut = false;
    const timeoutId = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, timeout);

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
        throw NexusPayError.fromEnvelope(raw, res.status, retryAfterSecondsFromResponse(res));
      }

      return (await res.json()) as T;
    } catch (err) {
      if (isAbortError(err)) {
        // A timeout abort is a transient network error (retryable); a
        // caller-signal abort is an intentional cancellation (not retryable) and
        // is rethrown unchanged.
        if (timedOut) {
          throw new NexusPayError({
            type: 'network_error',
            code: 'timeout',
            message: 'Request timed out',
          });
        }
        throw err;
      }
      // A fetch transport failure (TypeError etc.) is a retryable network error;
      // tag it so the retry layer can recognize it, then rethrow.
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

/** Reads a numeric `Retry-After` (delta-seconds) header; undefined otherwise. */
function retryAfterSecondsFromResponse(res: { headers?: { get?: (n: string) => string | null } }): number | undefined {
  const get = res?.headers?.get;
  if (typeof get !== 'function') return undefined;
  const raw = get.call(res.headers, 'retry-after');
  if (raw == null) return undefined;
  const seconds = Number(raw.trim());
  if (Number.isFinite(seconds) && seconds >= 0) return seconds;
  // HTTP-date form: best-effort delta from now (never negative).
  const when = Date.parse(raw);
  if (!Number.isNaN(when)) {
    const delta = Math.ceil((when - Date.now()) / 1000);
    return delta > 0 ? delta : 0;
  }
  return undefined;
}

/**
 * Decides whether an error from a single attempt is retryable and, if so, the
 * backoff (ms) before the next attempt. Returns undefined for non-retryable
 * failures (any 4xx except 429, caller-signal aborts, or a thrown
 * NexusPayError that is neither rate-limit nor 5xx nor network).
 *
 * Retryable: 429 (honoring numeric Retry-After), 5xx, local network/timeout
 * errors, and raw fetch transport failures (TypeError). `attempt` is 0-based
 * (0 = the failure of the initial try).
 */
function retryableDelayMs(err: unknown, attempt: number): number | undefined {
  const backoff = expoBackoffMs(attempt);

  if (err instanceof NexusPayError) {
    if (err.status === 429) {
      const ra = err.retryAfterSeconds;
      return typeof ra === 'number' && ra >= 0 ? ra * 1000 : backoff;
    }
    if (typeof err.status === 'number' && err.status >= 500 && err.status <= 599) {
      return backoff;
    }
    // Local timeout/transport mapped to network_error (no status) is retryable.
    if (err.status === undefined && err.type === 'network_error') {
      return backoff;
    }
    // Any other server error (4xx incl. 4xx envelopes) is terminal.
    return undefined;
  }

  // A caller-signal abort (intentional cancellation) is not retryable.
  if (isAbortError(err)) return undefined;

  // A raw fetch transport failure (e.g. TypeError: network error) is retryable.
  if (err instanceof TypeError) return backoff;

  return undefined;
}

/** Exponential backoff (ms) for a 0-based attempt index, capped. */
function expoBackoffMs(attempt: number): number {
  const ms = RETRY_BASE_DELAY_MS * 2 ** attempt;
  return Math.min(ms, RETRY_MAX_DELAY_MS);
}

/**
 * Reconciles the two capture inputs into the single wire pair. `captureMethod`
 * is authoritative; `capture` is the boolean alias (true -> 'automatic',
 * false -> 'manual'). Throws a NexusPayError if both are present and disagree.
 *
 * Back-compat: when only one input is set, the OTHER stays undefined (and is
 * dropped by compact()), so the wire body is byte-identical to the pre-DX-2
 * behavior — `{capture:true}` still sends only `capture`, `{captureMethod:'manual'}`
 * still sends only `capture_method`.
 */
function resolveCapture(
  capture: boolean | undefined,
  captureMethod: CaptureMethod | undefined,
): { capture?: boolean; captureMethod?: CaptureMethod } {
  if (capture !== undefined && captureMethod !== undefined) {
    const impliedByBoolean: CaptureMethod = capture ? 'automatic' : 'manual';
    if (impliedByBoolean !== captureMethod) {
      throw new NexusPayError({
        type: 'validation_error',
        code: 'capture_conflict',
        message:
          `createPayment: conflicting capture options — capture:${capture} implies ` +
          `captureMethod:'${impliedByBoolean}', but captureMethod:'${captureMethod}' was ` +
          `also passed. Set only one (captureMethod is authoritative), or make them agree.`,
      });
    }
    // They agree: forward both (unchanged shape when caller supplied both).
    return { capture, captureMethod };
  }
  // Exactly one (or neither) set: pass it through, leave the other undefined.
  return { capture, captureMethod };
}
