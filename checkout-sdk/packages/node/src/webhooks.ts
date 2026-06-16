/**
 * Webhook signature verification for @nexuspay/node.
 *
 * Byte-for-byte mirror of the platform signer
 * (gateway-api WebhookDeliveryService.computeSignature):
 *   HMAC-SHA256, key = secret UTF-8 bytes, over the exact payload UTF-8 bytes,
 *   output = lowercase bare hex (HexFormat.of().formatHex), NO "sha256=" prefix.
 *
 * The platform sends:
 *   X-NexusPay-Signature  -> bare lowercase hex
 *   X-NexusPay-Timestamp  -> Instant.now().toString() (ISO-8601)
 *   X-NexusPay-Event      -> dotted event type
 *
 * IMPORTANT: callers MUST pass the raw, unparsed request body. Re-serializing
 * the JSON changes the bytes and invalidates the signature.
 */

import { createHmac, timingSafeEqual } from 'node:crypto';
import { SignatureVerificationError } from './errors';
import type { WebhookEvent } from './types';

const SIGNATURE_HEADER = 'x-nexuspay-signature';
const TIMESTAMP_HEADER = 'x-nexuspay-timestamp';

type HeaderBag = Record<string, string | string[] | undefined>;

/**
 * HMAC-SHA256 hex of the EXACT raw body, lowercase, no prefix.
 * A string is UTF-8 encoded by Node (matches Java's getBytes(UTF_8));
 * a Buffer is used as-is.
 */
function computeSignature(rawBody: string | Buffer, secret: string): string {
  return createHmac('sha256', secret).update(rawBody).digest('hex');
}

/** Strips an optional, case-insensitive `sha256=` prefix and trims. */
function normalizeSignature(signatureHeader: string): string {
  const trimmed = signatureHeader.trim();
  if (/^sha256=/i.test(trimmed)) {
    return trimmed.slice('sha256='.length).trim();
  }
  return trimmed;
}

/** Timing-safe hex comparison. Returns false on length mismatch or bad hex. */
function timingSafeHexEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  try {
    const bufA = Buffer.from(a, 'hex');
    const bufB = Buffer.from(b, 'hex');
    // Buffer.from('zz','hex') silently drops invalid pairs; a length mismatch
    // here means the input was not valid hex of the expected length.
    if (bufA.length !== bufB.length || bufA.length === 0) return false;
    return timingSafeEqual(bufA, bufB);
  } catch {
    return false;
  }
}

/**
 * Verifies a webhook signature. Accepts a bare-hex or `sha256=`-prefixed
 * signature header. Never throws — returns a boolean.
 */
export function verifyWebhook(
  rawBody: string | Buffer,
  signatureHeader: string,
  secret: string,
): boolean {
  try {
    if (!signatureHeader) return false;
    const provided = normalizeSignature(signatureHeader);
    if (!provided) return false;
    const expected = computeSignature(rawBody, secret);
    return timingSafeHexEqual(expected, provided);
  } catch {
    return false;
  }
}

export interface ConstructEventOptions {
  /**
   * ADVISORY replay window vs the `X-NexusPay-Timestamp` header (seconds);
   * 0/undefined skips the check.
   *
   * ⚠️ SECURITY: this is NOT cryptographic replay protection. The platform
   * signs ONLY the raw body (WebhookDeliveryService.computeSignature) and sends
   * `X-NexusPay-Timestamp` as a SEPARATE, UNSIGNED header (`Instant.now()`),
   * which is NOT part of the HMAC input. An attacker who captures one valid
   * delivery can replay the exact body+signature while rewriting this header to
   * "now" — the check (and the signature) will both pass. Use this only as a
   * coarse freshness hint behind a trusted transport.
   *
   * For tamper-proof replay detection, use {@link createdToleranceSeconds},
   * which anchors on the signed `created` field inside the verified envelope.
   */
  toleranceSeconds?: number;
  /**
   * HARDENED replay window (seconds) anchored on the SIGNED `created` field of
   * the verified envelope (epoch seconds — WebhookEnvelopeSerializer.created),
   * which IS covered by the HMAC and therefore cannot be rewritten by a
   * replayer. Checked AFTER signature verification and JSON parse. 0/undefined
   * skips it. Prefer this over {@link toleranceSeconds} for real replay
   * protection; the two may be combined.
   */
  createdToleranceSeconds?: number;
  /** Injectable clock (epoch seconds) for tests. */
  now?: () => number;
}

function getHeader(bag: HeaderBag, name: string): string | undefined {
  const lower = name.toLowerCase();
  for (const [k, v] of Object.entries(bag)) {
    if (k.toLowerCase() === lower) {
      if (Array.isArray(v)) return v.length ? v[v.length - 1] : undefined;
      return v;
    }
  }
  return undefined;
}

/**
 * Verifies the signature, optionally enforces a replay window, then JSON-parses
 * the body. Throws SignatureVerificationError on any failure.
 *
 * Two independent replay windows are supported (see {@link ConstructEventOptions}):
 *   - `toleranceSeconds` — ADVISORY only, against the UNSIGNED timestamp header;
 *     a replayer can rewrite that header, so this is not cryptographic protection.
 *   - `createdToleranceSeconds` — HARDENED, against the SIGNED `created` field of
 *     the verified envelope (HMAC-covered, tamper-proof). Prefer this one.
 *
 * @param headersOrSignature a bare signature string, or a header bag (the
 *   X-NexusPay-Signature / X-NexusPay-Timestamp lookups are case-insensitive).
 */
export function constructEvent(
  rawBody: string | Buffer,
  headersOrSignature: string | HeaderBag,
  secret: string,
  options?: ConstructEventOptions,
): WebhookEvent {
  let signatureHeader: string | undefined;
  let timestampHeader: string | undefined;

  if (typeof headersOrSignature === 'string') {
    signatureHeader = headersOrSignature;
  } else {
    signatureHeader = getHeader(headersOrSignature, SIGNATURE_HEADER);
    timestampHeader = getHeader(headersOrSignature, TIMESTAMP_HEADER);
  }

  if (!signatureHeader || !signatureHeader.trim()) {
    throw new SignatureVerificationError(
      'Missing webhook signature header',
      'missing_signature',
    );
  }

  // ADVISORY replay window against the UNSIGNED timestamp header. This is not
  // cryptographic replay protection (the platform does not sign the timestamp —
  // see ConstructEventOptions.toleranceSeconds); prefer createdToleranceSeconds.
  const tolerance = options?.toleranceSeconds;
  if (tolerance && tolerance > 0) {
    if (!timestampHeader) {
      throw new SignatureVerificationError(
        'Missing webhook timestamp header',
        'timestamp_out_of_tolerance',
      );
    }
    const ts = Math.floor(Date.parse(timestampHeader) / 1000);
    if (Number.isNaN(ts)) {
      throw new SignatureVerificationError(
        'Invalid webhook timestamp header',
        'timestamp_out_of_tolerance',
      );
    }
    const now = options?.now ? options.now() : Math.floor(Date.now() / 1000);
    if (Math.abs(now - ts) > tolerance) {
      throw new SignatureVerificationError(
        'Webhook timestamp outside of tolerance',
        'timestamp_out_of_tolerance',
      );
    }
  }

  if (!verifyWebhook(rawBody, signatureHeader, secret)) {
    throw new SignatureVerificationError(
      'Webhook signature verification failed',
      'invalid_signature',
    );
  }

  const text = typeof rawBody === 'string' ? rawBody : rawBody.toString('utf8');
  let event: WebhookEvent;
  try {
    event = JSON.parse(text) as WebhookEvent;
  } catch {
    throw new SignatureVerificationError(
      'Webhook payload is not valid JSON',
      'invalid_payload',
    );
  }

  // HARDENED replay window: anchor on the SIGNED `created` field (epoch seconds)
  // of the now-verified envelope. Because `created` is inside the HMAC-covered
  // bytes, a replayer cannot rewrite it (unlike the X-NexusPay-Timestamp header).
  const createdTolerance = options?.createdToleranceSeconds;
  if (createdTolerance && createdTolerance > 0) {
    const created = event?.created;
    if (typeof created !== 'number' || !Number.isFinite(created)) {
      throw new SignatureVerificationError(
        'Webhook envelope is missing a numeric `created` field',
        'timestamp_out_of_tolerance',
      );
    }
    const now = options?.now ? options.now() : Math.floor(Date.now() / 1000);
    if (Math.abs(now - created) > createdTolerance) {
      throw new SignatureVerificationError(
        'Webhook `created` timestamp outside of tolerance',
        'timestamp_out_of_tolerance',
      );
    }
  }

  return event;
}
