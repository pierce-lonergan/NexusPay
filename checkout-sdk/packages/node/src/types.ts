/**
 * Request/response DTOs, webhook event types, and constants for @nexuspay/node.
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
