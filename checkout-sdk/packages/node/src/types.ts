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
  /**
   * TEST-3c: a saved payment-method id (`pm_...`) to charge OFF-SESSION (the cardholder is not
   * present). When set, the server resolves this tenant-owned saved method instead of the inline-card
   * path. Test recipe: attach `pm_card_visa` -> charge with `offSession: true` -> succeeds;
   * `pm_card_chargeDeclined` -> the payment fails (a `payment.failed` webhook).
   */
  paymentMethod?: string;
  /** TEST-3c: whether this is an off-session charge (cardholder not present). */
  offSession?: boolean;
  /** TEST-3c: intended future usage of the payment method. */
  setupFutureUsage?: 'off_session' | 'on_session';
  /**
   * TEST-3d: a cited mandate id (`mandate_…`) is a VALIDATED CONSENT GATE for an off-session charge. When
   * present it must be an `ACTIVE` mandate for the caller's tenant authorizing the charged `paymentMethod`
   * (`pm_…`): a foreign/missing mandate (or `paymentMethod`) -> 404; a non-ACTIVE mandate -> 400
   * `invalid_mandate`; a mandate authorizing a different `pm_` -> 400 `mandate_payment_method_mismatch`. The
   * gateway is never reached on any of these (no money moves). A null/absent `mandateId` is the 3c
   * pass-through (no consent gate).
   */
  mandateId?: string;
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

// ---- dispute (TEST-2) ----

/**
 * A server-side Dispute as returned by `GET /v1/disputes`, `GET /v1/disputes/{id}`,
 * and `POST /v1/test/disputes`. Field names mirror the gateway-api wire shape.
 */
export interface Dispute {
  id: string;
  tenant_id?: string;
  payment_id: string;
  external_dispute_id?: string;
  reason_code?: string;
  reason_description?: string;
  amount: number;
  currency: string;
  status: string;
  network?: string;
  outcome?: string;
  evidence_due_date?: string;
  evidence_submitted_at?: string;
  resolved_at?: string;
  created_at: string;
  updated_at: string;
}

/** One entry of a dispute's immutable event timeline (`GET /v1/disputes/{id}/events`). */
export interface DisputeEvent {
  id: string;
  dispute_id: string;
  event_type: string;
  old_status?: string;
  new_status?: string;
  actor?: string;
  details?: Metadata;
  created_at: string;
}

export interface ListDisputesParams {
  status?: string;
  limit?: number;
  offset?: number;
}

/**
 * Params for the TEST-MODE-only dispute simulator (`POST /v1/test/disputes`). Only
 * reachable with a TEST key (sk_test_); a live key is rejected. Opens a dispute
 * (chargeback) on a test payment under the caller's tenant so an integrator can
 * exercise dispute-webhook handling locally.
 */
export interface SimulateDisputeParams {
  paymentId: string;
  amount?: number;
  currency?: string;
  reason?: string;
}

// ---- customer (TEST-3a) ----

/**
 * A server-side Customer as returned by `POST /v1/customers`, `GET /v1/customers`,
 * `GET /v1/customers/{id}`, and `POST /v1/customers/{id}`. The wire body uses these exact keys;
 * `created` is epoch seconds. The tenant is NEVER present in the body.
 */
export interface Customer {
  id: string;
  object: 'customer';
  livemode: boolean;
  email?: string;
  name?: string;
  description?: string;
  metadata?: Metadata;
  created: number;
}

export interface CustomerCreateParams {
  email?: string;
  name?: string;
  description?: string;
  metadata?: Metadata;
}

export interface CustomerUpdateParams {
  email?: string;
  name?: string;
  description?: string;
  metadata?: Metadata;
}

export interface ListCustomersParams {
  limit?: number;
  offset?: number;
}

/** Body returned by `DELETE /v1/customers/{id}` (soft delete). */
export interface DeletedCustomer {
  id: string;
  object: 'customer';
  deleted: true;
}

// ---- payment methods (TEST-3b) ----

/**
 * The display surface of a saved payment method (snake_case wire shape). NEVER carries a raw PAN — only
 * the brand/last4/expiry/funding a saved card legitimately exposes.
 */
export interface PaymentMethodCard {
  brand?: string;
  last4?: string;
  exp_month?: number;
  exp_year?: number;
  funding?: string;
}

/**
 * A saved, multi-use payment method attached to a customer, as returned by
 * `POST /v1/customers/{customerId}/payment_methods`, `GET /v1/customers/{customerId}/payment_methods`,
 * and `GET /v1/payment_methods/{id}`. `customer` is the `cus_` id; `created` is epoch seconds. The
 * tenant and the opaque credential_ref are NEVER present in the body.
 */
export interface PaymentMethod {
  id: string;
  object: 'payment_method';
  livemode: boolean;
  type: string;
  customer: string;
  card?: PaymentMethodCard;
  metadata?: Metadata;
  created: number;
}

/**
 * Params for `attachPaymentMethod`. `credentialRef` is a TEST-mode fixture token (e.g. `pm_card_visa`)
 * under a test key, or an opaque pre-tokenized reference (e.g. a `ptok_`/PSP pm id) under a live key —
 * NEVER a raw card number. Display fields are used only on the live/opaque path (the fixture is
 * authoritative in test mode). There is deliberately NO number/cvc/pan field.
 */
export interface AttachPaymentMethodParams {
  type?: string;
  credentialRef: string;
  brand?: string;
  last4?: string;
  expMonth?: number;
  expYear?: number;
  funding?: string;
  metadata?: Metadata;
}

export interface ListPaymentMethodsParams {
  limit?: number;
  offset?: number;
}

/** Body returned by `DELETE /v1/payment_methods/{id}` (detach = soft delete). */
export interface DeletedPaymentMethod {
  id: string;
  object: 'payment_method';
  deleted: true;
}

// ---- mandates (TEST-3d) ----

/**
 * A mandate / consent record — the recorded off-session consent of the saved-credential cluster, as
 * returned by `POST /v1/mandates`, `GET /v1/mandates`, `GET /v1/mandates/{id}`, and
 * `POST /v1/mandates/{id}/revoke`. `customer` is the `cus_` id (derived from the `pm_`'s owner);
 * `payment_method` is the authorized `pm_`; `created` is epoch seconds. The tenant is NEVER present in the
 * body. `status` is one of `PENDING` / `ACTIVE` / `INACTIVE`; `type` is `MULTI_USE` / `SINGLE_USE`.
 *
 * NOTE: `type` is a recorded DESCRIPTIVE hint, not an enforced control. A `SINGLE_USE` mandate is NOT
 * self-consumed — it stays `ACTIVE` after an off-session charge and the consent gate (tenant + ACTIVE +
 * matching `payment_method`) does not consider `type`, so a `SINGLE_USE` mandate can be cited on more than
 * one off-session charge. Do not rely on single-use enforcement; revoke the mandate to stop further use.
 */
export interface Mandate {
  id: string;
  object: 'mandate';
  livemode: boolean;
  status: string;
  /** `MULTI_USE` / `SINGLE_USE`. Descriptive hint only — `SINGLE_USE` is NOT enforced (not self-consumed). */
  type: string;
  customer: string;
  payment_method: string;
  scenario?: string;
  created: number;
}

/**
 * Params for `createMandate`. `paymentMethod` is the saved method (`pm_…`) the consent authorizes; the
 * mandate's customer is derived from it server-side. `type` defaults to `MULTI_USE`. There is no tenant or
 * customer field — both are server-derived. NOTE: `SINGLE_USE` is a descriptive hint only — it is NOT
 * enforced (the mandate is not self-consumed and may be cited on more than one off-session charge).
 */
export interface MandateCreateParams {
  paymentMethod: string;
  /** `MULTI_USE` (default) / `SINGLE_USE`. `SINGLE_USE` is a descriptive hint only — NOT enforced. */
  type?: string;
  scenario?: string;
  metadata?: Metadata;
}

export interface ListMandatesParams {
  limit?: number;
  offset?: number;
}

/**
 * Body returned by `POST /v1/mandates/{id}/revoke` — the full mandate body, now `status: 'INACTIVE'`
 * (revoke is NOT a soft delete; the mandate stays retrievable).
 */
export interface RevokedMandate extends Mandate {}

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
  // TEST-2 dispute / chargeback lifecycle. MUST stay in sync with the platform
  // WebhookEventTaxonomy.CANONICAL set — WebhookEventTaxonomyParityTest fails CI on drift.
  'dispute.created',
  'dispute.funds_withdrawn',
  'dispute.evidence_needed',
  'dispute.evidence_submitted',
  'dispute.won',
  'dispute.lost',
  'dispute.closed',
] as const;

export type WebhookEventType = (typeof WEBHOOK_EVENT_TYPES)[number];

export interface WebhookEventObject {
  id: string;
  object: 'payment' | 'refund' | 'dispute';
  [k: string]: unknown;
}

/**
 * The `data.object` carried by a `dispute.*` webhook (TEST-2). Mirrors the
 * gateway-api dispute payload: minor-unit `amount`, the originating `payment_id`,
 * the dispute `status`, the network `reason`, and the evidence deadline.
 */
export interface DisputeWebhookObject extends WebhookEventObject {
  object: 'dispute';
  dispute_id: string;
  payment_id?: string;
  amount?: number;
  currency?: string;
  status?: string;
  reason?: string;
  evidence_due_by?: string;
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
