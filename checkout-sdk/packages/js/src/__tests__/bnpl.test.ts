// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { BnplProvider } from '../apm/bnpl';

/**
 * GAP-052: the BNPL provider loader `<script>` must carry the decided per-provider supply-chain
 * posture — crossOrigin='anonymous', a referrerPolicy, and NO integrity (SRI intentionally omitted
 * for the auto-updating loaders) — and must dedup a second load of the same URL.
 *
 * jsdom never fires a real network `<script>` `onload`, so we capture the element SYNCHRONOUSLY at
 * `document.head.appendChild` (via a spy) and then dispatch a synthetic `load` to let the promise
 * settle — never awaiting an unmocked network load (which would hang).
 *
 * Each test loads the module FRESH via `vi.resetModules()` + dynamic import, so the module-level
 * loaded-script dedup Set does not leak state between tests.
 */
describe('BnplHandler — GAP-052 loader script hardening', () => {
  let appended: HTMLScriptElement[];
  let appendSpy: ReturnType<typeof vi.spyOn>;

  const EXPECTED: Record<BnplProvider, { src: string }> = {
    klarna: { src: 'https://x.klarnacdn.net/kp/lib/v1/api.js' },
    afterpay: { src: 'https://js.afterpay.com/afterpay-1.x.js' },
    affirm: { src: 'https://cdn1.affirm.com/js/v2/affirm.js' },
  };

  const baseConfig = (provider: BnplProvider) => ({
    provider,
    amount: 1000,
    currency: 'USD',
    returnUrl: 'https://merchant.example/return',
  });

  async function freshHandler(provider: BnplProvider) {
    vi.resetModules();
    const { BnplHandler } = await import('../apm/bnpl');
    return new BnplHandler(baseConfig(provider));
  }

  beforeEach(() => {
    appended = [];
    appendSpy = vi
      .spyOn(document.head, 'appendChild')
      .mockImplementation(((node: Node) => {
        appended.push(node as HTMLScriptElement);
        // Fire load asynchronously so loadProviderScript's promise resolves without a real network.
        queueMicrotask(() => {
          (node as HTMLScriptElement).onload?.(new Event('load'));
        });
        return node;
      }) as typeof document.head.appendChild);
  });

  afterEach(() => {
    appendSpy.mockRestore();
    vi.restoreAllMocks();
    document.body.innerHTML = '';
  });

  (['klarna', 'afterpay', 'affirm'] as BnplProvider[]).forEach((provider) => {
    it(`${provider}: script carries expected src, crossOrigin='anonymous', referrerPolicy, and NO integrity`, async () => {
      const handler = await freshHandler(provider);

      await handler.loadProviderScript();

      expect(appended).toHaveLength(1);
      const script = appended[0];
      expect(script.src).toBe(EXPECTED[provider].src);
      expect(script.async).toBe(true);
      // Decided posture: CORS anonymous + a referrer policy that does not leak the checkout URL.
      expect(script.crossOrigin).toBe('anonymous');
      expect(script.referrerPolicy).toBe('no-referrer');
      // Decided posture: NO SRI on an auto-updating loader (would break on the provider's next push).
      // Assert the absence of the integrity ATTRIBUTE (the authoritative DOM state); the reflected
      // `.integrity` IDL property is not implemented in jsdom, so we assert via getAttribute.
      expect(script.getAttribute('integrity')).toBeNull();
    });

    it(`${provider}: descriptor exposes crossorigin+referrerpolicy and omits integrity`, async () => {
      const handler = await freshHandler(provider);
      const { descriptor } = handler.__test__;
      expect(descriptor.src).toBe(EXPECTED[provider].src);
      expect(descriptor.crossorigin).toBe('anonymous');
      expect(descriptor.referrerpolicy).toBe('no-referrer');
      expect(descriptor.integrity).toBeUndefined();
    });
  });

  it('dedups a second load of the same provider URL (no duplicate <script> appended)', async () => {
    vi.resetModules();
    const { BnplHandler } = await import('../apm/bnpl');

    const first = new BnplHandler(baseConfig('klarna'));
    await first.loadProviderScript();
    expect(appended).toHaveLength(1);

    // A second handler for the SAME provider must not append another <script> (module-level dedup set).
    const second = new BnplHandler(baseConfig('klarna'));
    await second.loadProviderScript();
    expect(appended).toHaveLength(1);
  });
});
