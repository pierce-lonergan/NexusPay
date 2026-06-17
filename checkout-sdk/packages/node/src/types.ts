/**
 * Request/response DTOs, webhook event types, and constants for @nexus-pay/node.
 *
 * Field names on response/webhook types mirror the gateway-api wire shape
 * (snake_case) so the parsed JSON conforms directly. Request *params* use
 * camelCase and are mapped to snake_case in the client request engine.
 */

// ---- shared ----
export type CaptureMethod = 'automatic' | 'manual';
export type Metadata = Record<string, unknown>;

// ---- payment session ----
export interface CreatePaymentSessionParams {
  amount: number;
  currency: string;
  customerId?: string;
  successUrl?: string;
  cancelUrl?: string;
  allowedPaymentMethods?: string[];
  branding?: Metadata;
  metadata?: Metadata;
}

export interface PaymentSession {
  id: string;
  status: string;
  amount: number;
  currency: string;
  customer_id?: string;
  payment_intent_id?: string;
  client_secret: string;
  allowed_payment_methods?: string[];
  success_url?: string;
  cancel_url?: string;
  branding?: Metadata;
  expires_at: string;
  created_at: string;
}

// ---- payment ----
export interface CreatePaymentParams {
  amount: number;
  currency: string;
  customerId?: string;
  paymentMethodType?: string;
  paymentMethodData?: string;
  returnUrl?: string;
  description?: string;
  /** Maps to `capture_method` (authoritative when both present). */
  captureMethod?: CaptureMethod;
  /** INT-2 boolean alias: true -> automatic, false -> manual. */
  capture?: boolean;
  metadata?: Metadata;
}

/**
 * A server-side Payment as returned by `POST /v1/payments` and the other
 * `/v1/payments/*` endpoints.
 *
 * NOTE: `POST /v1/payments` returns only the payment `id` (and the fields
 * below) — it does NOT return a `client_secret`. The `client_secret` needed to
 * mount the browser payment element / confirm from the client lives on a
 * {@link PaymentSession}: create one with
 * {@link NexusPay.createPaymentSession | createPaymentSession} and read
 * `PaymentSession.client_secret`. Do not look for a secret on this type.
 */
export interface Payment {
  id: string;
  status: string;
  amount: number;
  currency: string;
  capture_method?: CaptureMethod;
  customer_id?: string;
  connector?: string;
  error_code?: string;
  error_message?: string;
  created_at?: string;
  metadata?: Metadata;
  mode?: 'test' | 'live';
}

export interface ConfirmPaymentParams {
  paymentMethodType?: string;
  paymentMethodData?: string;
  returnUrl?: string;
}

export interface CapturePaymentParams {
  amountToCapture?: number;
}

export interface CancelPaymentParams {
  cancellationReason?: string;
}

// ---- refund (INT-2 201 | 202) ----
export interface CreateRefundParams {
  amount: number;
  currency?: string;
  reason?: string;
}

/** 201 body — refund did not require approval. */
export interface Refund {
  id: string;
  payment_id: string;
  status: string;
  amount: number;
  currency?: string;
  reason?: string;
  connector?: string;
  error_code?: string;
  error_message?: string;
  created_at?: string;
  requires_approval: false;
}

/** 202 body (ApprovalResponse) — refund exceeded the approval threshold. */
export interface RefundApproval {
  id: string;
  action: string;
  resource_type: string;
  resource_id: string;
  status: string;
  requested_by?: string;
  reviewed_by?: string;
  payload?: Metadata;
  created_at?: string;
  reviewed_at?: string;
  requires_approval: true;
  /** Minor-unit threshold above which approval is required. */
  approval_threshold: number;
}

/** Discriminated union on `requires_approval` — the caller narrows. */
export type CreateRefundResult = Refund | RefundApproval;

// ---- per-call options ----
export interface RequestOptions {
  idempotencyKey?: string;
  timeoutMs?: number;
  signal?: AbortSignal;
  /**
   * Opt-in automatic retry count for transient failures (429, 5xx, and
   * network/transport errors only — never other 4xx). Default 0, which is the
   * historical behavior: exactly one fetch, no auto-generated idempotency key.
   *
   * When > 0, retries use exponential backoff and honor a `Retry-After` header
   * on 429 responses. For a mutating (POST) request, a SINGLE `Idempotency-Key`
   * is reused across the whole attempt sequence; if {@link idempotencyKey} is
   * not supplied, one is auto-generated (crypto.randomUUID) for that sequence so
   * retries are safe by construction.
   *
   * Overrides {@link NexusPayOptions.maxRetries} for this call when set.
   */
  maxRetries?: number;
}

// ---- webhooks ----
export const WEBHOOK_EVENT_TYPES = [
  'payment.created',
  'payment.authorized',
  'payment.succeeded',
  'payment.failed',
  'payment.canceled',
  'payment.refund.created',
  'payment.refunded',
  'payment.refund.failed',
] as const;

export type WebhookEventType = (typeof WEBHOOK_EVENT_TYPES)[number];

export interface WebhookEventObject {
  id: string;
  object: 'payment' | 'refund';
  [k: string]: unknown;
}

export interface WebhookEvent<T extends WebhookEventObject = WebhookEventObject> {
  id: string;
  type: WebhookEventType;
  livemode: boolean;
  /** Epoch seconds. */
  created: number;
  /** e.g. "2026-06-16". */
  api_version: string;
  data: { object: T; metadata: Metadata };
}
