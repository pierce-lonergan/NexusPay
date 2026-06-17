/**
 * Webhook signature verification for @nexus-pay/node.
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

type PlainHeaderBag = Record<string, string | string[] | undefined>;

/** Minimal structural type for a WHATWG `Headers` (or anything with `.get`). */
interface HeadersLike {
  get(name: string): string | null;
}

/**
 * Accepted header inputs: a plain object / Node `IncomingHttpHeaders`-style bag,
 * OR a WHATWG `Headers` instance (e.g. Next.js App Router `req.headers`,
 * Fetch/Web Request). Both are resolved case-insensitively.
 */
type HeaderBag = PlainHeaderBag | HeadersLike;

/** True for a WHATWG `Headers` (or any object exposing a `.get(name)` method). */
function isHeadersLike(bag: unknown): bag is HeadersLike {
  return (
    typeof bag === 'object' &&
    bag !== null &&
    typeof (bag as { get?: unknown }).get === 'function'
  );
}

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
   * ✅ CANONICAL replay window. HARDENED window (seconds) anchored on the SIGNED
   * `created` field of the verified envelope (epoch seconds —
   * WebhookEnvelopeSerializer.created), which IS covered by the HMAC and
   * therefore cannot be rewritten by a replayer. Checked AFTER signature
   * verification and JSON parse. 0/undefined skips it.
   *
   * This is the recommended path for real replay protection. Prefer it over the
   * `*HeaderToleranceSeconds` options below, which verify only the UNSIGNED
   * timestamp header and are defeatable by an attacker.
   */
  createdToleranceSeconds?: number;
  /**
   * ⚠️ UNSAFE / ADVISORY ONLY — replay window vs the UNSIGNED
   * `X-NexusPay-Timestamp` header (seconds); 0/undefined skips the check.
   *
   * SECURITY: this is NOT cryptographic replay protection and is
   * attacker-defeatable. The platform signs ONLY the raw body
   * (WebhookDeliveryService.computeSignature) and sends `X-NexusPay-Timestamp`
   * as a SEPARATE, UNSIGNED header (`Instant.now()`) that is NOT part of the
   * HMAC input. An attacker who captures one valid delivery can replay the exact
   * body+signature while rewriting this header to "now" — both this check and
   * the signature will pass. Use ONLY as a coarse freshness hint behind a
   * trusted transport, never as a security boundary.
   *
   * For tamper-proof replay detection use {@link createdToleranceSeconds}, which
   * anchors on the signed `created` field inside the verified envelope. This
   * option is honored identically to the deprecated {@link toleranceSeconds};
   * if both are supplied, the larger (more permissive) window is used.
   */
  unsafeHeaderToleranceSeconds?: number;
  /**
   * @deprecated Use {@link createdToleranceSeconds} (signed, tamper-proof) for
   * real replay protection, or {@link unsafeHeaderToleranceSeconds} if you
   * explicitly want the old advisory header check. Retained for back-compat and
   * still honored identically to `unsafeHeaderToleranceSeconds`.
   *
   * ⚠️ SECURITY: verifies the UNSIGNED `X-NexusPay-Timestamp` header and is
   * attacker-defeatable — see {@link unsafeHeaderToleranceSeconds} for the full
   * threat model. This is NOT cryptographic replay protection.
   */
  toleranceSeconds?: number;
  /** Injectable clock (epoch seconds) for tests. */
  now?: () => number;
}

function getHeader(bag: HeaderBag, name: string): string | undefined {
  // WHATWG `Headers` (Next.js App Router, Fetch Request): `.get` is already
  // case-insensitive and joins multiple values. Object.entries(headers) returns
  // [] for a Headers instance, so without this branch every App-Router delivery
  // resolved to `missing_signature`.
  if (isHeadersLike(bag)) {
    const v = bag.get(name);
    return v === null ? undefined : v;
  }
  const lower = name.toLowerCase();
  for (const [k, v] of Object.entries(bag)) {
    if (k.toLowerCase() === lower) {
      if (Array.isArray(v)) return v.length ? v[v.length - 1] : undefined;
      return v;
    }
  }
  return undefined;
}

/** Largest of the defined numeric inputs, or undefined if none are defined. */
function maxDefined(...values: Array<number | undefined>): number | undefined {
  let out: number | undefined;
  for (const v of values) {
    if (typeof v === 'number' && (out === undefined || v > out)) out = v;
  }
  return out;
}

/**
 * Verifies the signature, optionally enforces a replay window, then JSON-parses
 * the body. Throws SignatureVerificationError on any failure.
 *
 * Two independent replay windows are supported (see {@link ConstructEventOptions}):
 *   - `createdToleranceSeconds` — CANONICAL/HARDENED, against the SIGNED `created`
 *     field of the verified envelope (HMAC-covered, tamper-proof). Prefer this one.
 *   - `unsafeHeaderToleranceSeconds` (and its deprecated synonym `toleranceSeconds`)
 *     — ADVISORY only, against the UNSIGNED timestamp header; a replayer can rewrite
 *     that header, so this is NOT cryptographic protection.
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
  // see ConstructEventOptions.unsafeHeaderToleranceSeconds); prefer
  // createdToleranceSeconds. `toleranceSeconds` is the deprecated synonym of
  // `unsafeHeaderToleranceSeconds`; if both are set, use the larger window so
  // neither caller intent silently tightens the other.
  const tolerance = maxDefined(
    options?.unsafeHeaderToleranceSeconds,
    options?.toleranceSeconds,
  );
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
