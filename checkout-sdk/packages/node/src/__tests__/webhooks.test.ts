import { describe, it, expect } from 'vitest';
import { createHmac } from 'node:crypto';
import { verifyWebhook, constructEvent } from '../webhooks';
import { SignatureVerificationError } from '../errors';

/**
 * Reproduce the EXACT platform signing algorithm in-test
 * (WebhookDeliveryService.computeSignature): HmacSHA256 over the UTF-8 payload
 * bytes, lowercase bare hex, no prefix. If the SDK drifts from this, these
 * tests fail.
 */
function sign(body: string, secret: string): string {
  return createHmac('sha256', secret).update(body, 'utf8').digest('hex');
}

const SECRET = 'whsec_test_secret_123';

/** A real INT-1 canonical envelope (livemode before created per serializer). */
function canonicalEnvelope(): string {
  return JSON.stringify({
    id: 'evt_abc123',
    type: 'payment.succeeded',
    livemode: true,
    created: 1718553600,
    api_version: '2026-06-16',
    data: {
      object: { id: 'pay_1', object: 'payment', amount: 1000, currency: 'usd' },
      metadata: { order_id: 'ord_42' },
    },
  });
}

describe('verifyWebhook', () => {
  it('accepts a correctly-signed body (bare hex)', () => {
    const body = canonicalEnvelope();
    expect(verifyWebhook(body, sign(body, SECRET), SECRET)).toBe(true);
  });

  it('accepts a sha256=-prefixed signature', () => {
    const body = canonicalEnvelope();
    expect(verifyWebhook(body, `sha256=${sign(body, SECRET)}`, SECRET)).toBe(true);
  });

  it('accepts a Buffer body', () => {
    const body = canonicalEnvelope();
    const buf = Buffer.from(body, 'utf8');
    expect(verifyWebhook(buf, sign(body, SECRET), SECRET)).toBe(true);
  });

  it('rejects a tampered body', () => {
    const body = canonicalEnvelope();
    const sig = sign(body, SECRET);
    const tampered = body.replace('1000', '9999');
    expect(verifyWebhook(tampered, sig, SECRET)).toBe(false);
  });

  it('rejects a signature made with the wrong secret', () => {
    const body = canonicalEnvelope();
    expect(verifyWebhook(body, sign(body, 'wrong_secret'), SECRET)).toBe(false);
  });

  it('rejects a missing / empty signature', () => {
    const body = canonicalEnvelope();
    expect(verifyWebhook(body, '', SECRET)).toBe(false);
  });

  it('rejects a malformed (non-hex) signature without throwing', () => {
    const body = canonicalEnvelope();
    expect(verifyWebhook(body, 'not-hex-zzzz', SECRET)).toBe(false);
  });

  it('rejects a truncated signature (length mismatch, timing-safe)', () => {
    const body = canonicalEnvelope();
    const sig = sign(body, SECRET);
    expect(verifyWebhook(body, sig.slice(0, sig.length - 2), SECRET)).toBe(false);
  });
});

describe('constructEvent', () => {
  it('round-trips a real canonical envelope into a typed event', () => {
    const body = canonicalEnvelope();
    const event = constructEvent(body, sign(body, SECRET), SECRET);
    expect(event.type).toBe('payment.succeeded');
    expect(event.livemode).toBe(true);
    expect(event.created).toBe(1718553600);
    expect(event.api_version).toBe('2026-06-16');
    expect(event.data.object.id).toBe('pay_1');
    expect(event.data.metadata).toEqual({ order_id: 'ord_42' });
  });

  it('extracts X-NexusPay-Signature case-insensitively from a header bag', () => {
    const body = canonicalEnvelope();
    const event = constructEvent(
      body,
      { 'X-NexusPay-Signature': sign(body, SECRET) },
      SECRET,
    );
    expect(event.id).toBe('evt_abc123');
  });

  it('handles an array-valued header (takes the last value)', () => {
    const body = canonicalEnvelope();
    const event = constructEvent(
      body,
      { 'x-nexuspay-signature': ['garbage', sign(body, SECRET)] },
      SECRET,
    );
    expect(event.id).toBe('evt_abc123');
  });

  it('throws invalid_signature on forgery', () => {
    const body = canonicalEnvelope();
    try {
      constructEvent(body, sign(body, 'wrong'), SECRET);
      expect.unreachable('should have thrown');
    } catch (err) {
      expect(err).toBeInstanceOf(SignatureVerificationError);
      expect((err as SignatureVerificationError).code).toBe('invalid_signature');
    }
  });

  it('throws missing_signature on an absent header', () => {
    const body = canonicalEnvelope();
    try {
      constructEvent(body, {}, SECRET);
      expect.unreachable('should have thrown');
    } catch (err) {
      expect((err as SignatureVerificationError).code).toBe('missing_signature');
    }
  });

  it('throws invalid_payload on a signed-but-non-JSON body', () => {
    const body = 'this is not json';
    try {
      constructEvent(body, sign(body, SECRET), SECRET);
      expect.unreachable('should have thrown');
    } catch (err) {
      expect((err as SignatureVerificationError).code).toBe('invalid_payload');
    }
  });

  it('rejects a stale timestamp outside the tolerance window', () => {
    const body = canonicalEnvelope();
    const sig = sign(body, SECRET);
    // Event timestamped at the canonical "created" instant; clock is far later.
    const ts = '2026-06-16T16:00:00Z';
    try {
      constructEvent(
        body,
        { 'x-nexuspay-signature': sig, 'x-nexuspay-timestamp': ts },
        SECRET,
        { toleranceSeconds: 300, now: () => Date.parse(ts) / 1000 + 10_000 },
      );
      expect.unreachable('should have thrown');
    } catch (err) {
      expect((err as SignatureVerificationError).code).toBe('timestamp_out_of_tolerance');
    }
  });

  it('accepts a timestamp within the tolerance window', () => {
    const body = canonicalEnvelope();
    const sig = sign(body, SECRET);
    const ts = '2026-06-16T16:00:00Z';
    const event = constructEvent(
      body,
      { 'x-nexuspay-signature': sig, 'x-nexuspay-timestamp': ts },
      SECRET,
      { toleranceSeconds: 300, now: () => Date.parse(ts) / 1000 + 60 },
    );
    expect(event.type).toBe('payment.succeeded');
  });

  describe('createdToleranceSeconds (signed, tamper-proof replay window)', () => {
    // The canonical envelope's signed `created` is 1718553600.
    const CREATED = 1718553600;

    it('rejects a replayed envelope whose signed `created` is stale', () => {
      const body = canonicalEnvelope();
      const sig = sign(body, SECRET);
      try {
        constructEvent(body, sig, SECRET, {
          createdToleranceSeconds: 300,
          now: () => CREATED + 10_000,
        });
        expect.unreachable('should have thrown');
      } catch (err) {
        expect(err).toBeInstanceOf(SignatureVerificationError);
        expect((err as SignatureVerificationError).code).toBe('timestamp_out_of_tolerance');
      }
    });

    it('accepts an envelope whose signed `created` is fresh', () => {
      const body = canonicalEnvelope();
      const sig = sign(body, SECRET);
      const event = constructEvent(body, sig, SECRET, {
        createdToleranceSeconds: 300,
        now: () => CREATED + 60,
      });
      expect(event.type).toBe('payment.succeeded');
    });

    it('is unaffected by a rewritten (unsigned) timestamp header — anchors on signed `created`', () => {
      const body = canonicalEnvelope();
      const sig = sign(body, SECRET);
      // Attacker rewrites X-NexusPay-Timestamp to "now" but cannot change the
      // signed `created`; the hardened window still rejects the stale event.
      const freshHeaderTs = new Date((CREATED + 10_000) * 1000).toISOString();
      try {
        constructEvent(
          body,
          { 'x-nexuspay-signature': sig, 'x-nexuspay-timestamp': freshHeaderTs },
          SECRET,
          { createdToleranceSeconds: 300, now: () => CREATED + 10_000 },
        );
        expect.unreachable('should have thrown');
      } catch (err) {
        expect((err as SignatureVerificationError).code).toBe('timestamp_out_of_tolerance');
      }
    });
  });
});
