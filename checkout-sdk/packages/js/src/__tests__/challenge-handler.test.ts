// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { ChallengeHandler } from '../three-ds/challenge-handler';

const CHALLENGE_URL = 'https://acs.issuer.example.com/3ds/challenge?id=abc';
const CHALLENGE_ORIGIN = 'https://acs.issuer.example.com';

function completeEvent(origin: string, status: 'succeeded' | 'failed'): MessageEvent {
  return new MessageEvent('message', {
    origin,
    data: { source: 'nexuspay-3ds', type: 'CHALLENGE_COMPLETE', payload: { status } },
  });
}

describe('ChallengeHandler B-021 — URL validation', () => {
  let handler: ChallengeHandler;

  beforeEach(() => {
    handler = new ChallengeHandler();
  });

  afterEach(() => {
    handler.cancel();
    document.body.innerHTML = '';
  });

  it('isSafeChallengeUrl accepts an absolute https URL on a real host', () => {
    expect(ChallengeHandler.isSafeChallengeUrl('https://acs.example.com/3ds')).toBe(true);
  });

  it.each([
    ['javascript: scheme', 'javascript:alert(document.cookie)'],
    ['data: scheme', 'data:text/html,<script>alert(1)</script>'],
    ['http (non-TLS)', 'http://acs.example.com/3ds'],
    ['protocol-relative', '//evil.example.com/3ds'],
    ['relative path', '/3ds/challenge'],
    ['bare word', 'challenge'],
    ['empty', ''],
    ['whitespace', '   '],
  ])('isSafeChallengeUrl rejects %s', (_label, url) => {
    expect(ChallengeHandler.isSafeChallengeUrl(url)).toBe(false);
  });

  it('handle() rejects an unsafe redirect URL without navigating', async () => {
    const result = await handler.handle({ type: 'redirect', url: 'javascript:alert(1)' });
    expect(result.status).toBe('failed');
    expect(result.error).toMatch(/unsafe|invalid/i);
  });

  it('handle() rejects an unsafe three_d_secure URL without iframing', async () => {
    const result = await handler.handle({ type: 'three_d_secure', url: '//evil.example.com' });
    expect(result.status).toBe('failed');
    expect(result.error).toMatch(/unsafe|invalid/i);
    // No iframe should have been created.
    expect(document.querySelector('iframe')).toBeNull();
  });
});

describe('ChallengeHandler B-021 — 3DS completion origin gate', () => {
  let handler: ChallengeHandler;

  beforeEach(() => {
    handler = new ChallengeHandler();
  });

  afterEach(() => {
    handler.cancel();
    document.body.innerHTML = '';
  });

  it('IGNORES a CHALLENGE_COMPLETE from a foreign origin', async () => {
    const resultPromise = handler.handle({ type: 'three_d_secure', url: CHALLENGE_URL });

    // Attacker (any other origin) tries to force a success.
    window.dispatchEvent(completeEvent('https://evil.example.com', 'succeeded'));

    // The promise must NOT resolve from the spoofed message. Race it against a
    // microtask flush; if it resolved we'd get the spoofed result.
    const settled = await Promise.race([
      resultPromise.then((r) => ({ resolved: true as const, r })),
      Promise.resolve({ resolved: false as const }),
    ]);
    expect(settled.resolved).toBe(false);
  });

  it('ACCEPTS a CHALLENGE_COMPLETE from the nextAction.url origin', async () => {
    const resultPromise = handler.handle({ type: 'three_d_secure', url: CHALLENGE_URL });

    window.dispatchEvent(completeEvent(CHALLENGE_ORIGIN, 'succeeded'));

    const result = await resultPromise;
    expect(result.status).toBe('succeeded');
  });

  it('a foreign-origin message does not block the legitimate one that follows', async () => {
    const resultPromise = handler.handle({ type: 'three_d_secure', url: CHALLENGE_URL });

    window.dispatchEvent(completeEvent('https://evil.example.com', 'succeeded'));
    window.dispatchEvent(completeEvent(CHALLENGE_ORIGIN, 'failed'));

    const result = await resultPromise;
    // Resolves from the legit (matching-origin) message, not the attacker's.
    expect(result.status).toBe('failed');
  });
});
