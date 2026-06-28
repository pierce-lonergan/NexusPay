/**
 * TEST-5 (E2): ready-made, TYPED sample webhook events — one per
 * {@link WEBHOOK_EVENT_TYPES} value — so an integrator can unit-test their
 * webhook handler against a realistic `WebhookEvent` without driving a real
 * payment or a live delivery.
 *
 * Kept separate from `types.ts` so type-only consumers (callers who only import
 * the `WebhookEvent` type) stay dependency-free of these runtime fixtures.
 *
 * Typing: {@link testFixtures} is `Record<WebhookEventType, WebhookEvent>`, so a
 * MISSING key or an EXTRA/wrong key is a COMPILE error; each fixture's literal
 * `type` is checked against `WebhookEventType`, and a wrong `data.object`
 * discriminant or field is a compile error against `WebhookEvent` /
 * `DisputeWebhookObject`.
 */

import { WEBHOOK_EVENT_TYPES } from './types';
import type {
  WebhookEvent,
  WebhookEventObject,
  WebhookEventType,
  DisputeWebhookObject,
  Metadata,
} from './types';

/** Matches `ApiVersion.CURRENT` on the platform — the contract version stamped into every envelope. */
const API_VERSION = '2026-06-16';
/** A fixed, deterministic `created` (epoch seconds) so fixtures are stable across runs. */
const CREATED = 1718553600;

/** Builds a payment-family `data.object`. */
function paymentObject(
  status: string,
  overrides: Partial<WebhookEventObject> = {},
): WebhookEventObject {
  return {
    id: 'pay_test_123',
    object: 'payment',
    amount: 1000,
    currency: 'USD',
    status,
    ...overrides,
  };
}

/** Builds a refund-family `data.object`. */
function refundObject(
  status: string,
  overrides: Partial<WebhookEventObject> = {},
): WebhookEventObject {
  return {
    id: 'ref_test_123',
    object: 'refund',
    payment_id: 'pay_test_123',
    amount: 1000,
    currency: 'USD',
    status,
    ...overrides,
  };
}

/** Builds a dispute-family `data.object` (typed so a wrong field is a compile error). */
function disputeObject(
  status: string,
  reason: string,
  overrides: Partial<DisputeWebhookObject> = {},
): DisputeWebhookObject {
  return {
    id: 'dp_test_123',
    object: 'dispute',
    dispute_id: 'dp_test_123',
    payment_id: 'pay_test_123',
    amount: 1000,
    currency: 'USD',
    status,
    reason,
    evidence_due_by: '2026-07-01T00:00:00Z',
    ...overrides,
  };
}

/** Wraps a `data.object` into a full, typed `WebhookEvent` envelope. */
function envelope<T extends WebhookEventObject>(
  type: WebhookEventType,
  idSuffix: string,
  object: T,
  metadata: Metadata = {},
): WebhookEvent<T> {
  return {
    id: `evt_test_${idSuffix}`,
    type,
    livemode: false,
    created: CREATED,
    api_version: API_VERSION,
    data: { object, metadata },
  };
}

/**
 * One realistic, typed sample `WebhookEvent` per canonical event type. The
 * `Record<WebhookEventType, WebhookEvent>` annotation forces this map to be
 * EXHAUSTIVE — adding a type to `WEBHOOK_EVENT_TYPES` without a fixture here is a
 * compile error.
 */
export const testFixtures: Record<WebhookEventType, WebhookEvent> = {
  'payment.created': envelope('payment.created', 'pay_created', paymentObject('requires_confirmation')),
  'payment.authorized': envelope('payment.authorized', 'pay_authorized', paymentObject('requires_capture')),
  'payment.succeeded': envelope('payment.succeeded', 'pay_succeeded', paymentObject('succeeded')),
  'payment.failed': envelope(
    'payment.failed',
    'pay_failed',
    paymentObject('failed', { error_code: 'card_declined', error_message: 'Your card was declined.' }),
  ),
  'payment.canceled': envelope('payment.canceled', 'pay_canceled', paymentObject('canceled')),
  'payment.refund.created': envelope('payment.refund.created', 'ref_created', refundObject('pending')),
  'payment.refunded': envelope('payment.refunded', 'pay_refunded', refundObject('succeeded')),
  'payment.refund.failed': envelope(
    'payment.refund.failed',
    'ref_failed',
    refundObject('failed', { error_code: 'refund_failed' }),
  ),
  'dispute.created': envelope('dispute.created', 'disp_created', disputeObject('needs_response', 'fraudulent')),
  'dispute.funds_withdrawn': envelope(
    'dispute.funds_withdrawn',
    'disp_funds',
    disputeObject('under_review', 'fraudulent'),
  ),
  'dispute.evidence_needed': envelope(
    'dispute.evidence_needed',
    'disp_evneeded',
    disputeObject('needs_response', 'product_not_received'),
  ),
  'dispute.evidence_submitted': envelope(
    'dispute.evidence_submitted',
    'disp_evsubmitted',
    disputeObject('under_review', 'product_not_received'),
  ),
  'dispute.won': envelope('dispute.won', 'disp_won', disputeObject('won', 'fraudulent')),
  'dispute.lost': envelope('dispute.lost', 'disp_lost', disputeObject('lost', 'fraudulent')),
  'dispute.closed': envelope('dispute.closed', 'disp_closed', disputeObject('closed', 'fraudulent')),
};

/**
 * A shallow-mergeable override shape for {@link buildTestEvent}. Top-level
 * `WebhookEvent` fields are partial; `data` (and `data.object`) are partial so a
 * test can tweak just `amount` / `id` without restating the whole object.
 * Typed against `WebhookEvent`, so a wrong field name is a compile error.
 */
export interface TestEventOverrides {
  id?: string;
  livemode?: boolean;
  created?: number;
  api_version?: string;
  data?: {
    object?: Partial<WebhookEventObject>;
    metadata?: Metadata;
  };
}

/**
 * TEST-5 (E2): clones the fixture for `type` and shallow-merges `overrides` so a
 * test can customize fields (e.g. `amount`, `id`, `metadata`). The `type` is
 * preserved (you select it by argument); `data.object` and `data.metadata` merge
 * over the fixture's. Returns a fresh object — the fixture is never mutated.
 *
 * @example
 * const evt = buildTestEvent('payment.succeeded', {
 *   data: { object: { amount: 999 } },
 * });
 * // evt.type === 'payment.succeeded', evt.data.object.amount === 999
 */
export function buildTestEvent<T extends WebhookEventType>(
  type: T,
  overrides?: TestEventOverrides,
): WebhookEvent {
  const base = testFixtures[type];
  return {
    ...base,
    ...(overrides?.id !== undefined ? { id: overrides.id } : {}),
    ...(overrides?.livemode !== undefined ? { livemode: overrides.livemode } : {}),
    ...(overrides?.created !== undefined ? { created: overrides.created } : {}),
    ...(overrides?.api_version !== undefined ? { api_version: overrides.api_version } : {}),
    // type stays the selected `type` — never overridable, so the discriminant is stable.
    type: base.type,
    data: {
      object: { ...base.data.object, ...(overrides?.data?.object ?? {}) },
      metadata: { ...base.data.metadata, ...(overrides?.data?.metadata ?? {}) },
    },
  };
}
