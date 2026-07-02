/**
 * BnplHandler — Buy Now, Pay Later integration.
 * Supports Klarna, Afterpay, Affirm with dynamic script loading
 * (Klarna SDK ~200KB loaded on-demand only when BNPL tab is selected).
 */

import { EventEmitter } from '../event-emitter';

interface BnplEvents {
  [key: string]: unknown;
  ready: void;
  token: BnplTokenResult;
  redirect: { url: string };
  cancel: void;
  error: { message: string };
}

export interface BnplTokenResult {
  type: 'bnpl';
  provider: BnplProvider;
  tokenData: string;
}

export type BnplProvider = 'klarna' | 'afterpay' | 'affirm';

export interface BnplConfig {
  provider: BnplProvider;
  apiBase?: string;
  sessionToken?: string;
  amount: number;
  currency: string;
  returnUrl: string;
  locale?: string;
}

const PROVIDER_LABELS: Record<BnplProvider, string> = {
  klarna: 'Klarna',
  afterpay: 'Afterpay',
  affirm: 'Affirm',
};

/**
 * GAP-052 — supply-chain hardening of the BNPL provider loader scripts.
 *
 * Descriptor per provider instead of a bare URL, so each loader `<script>` carries an explicit,
 * per-provider security posture. See {@link PROVIDER_SCRIPTS} for the per-provider rationale.
 *
 * `integrity` is intentionally OPTIONAL and currently UNSET for all three providers (see below). The
 * field exists so a Subresource Integrity hash can be added later for any provider that ships a
 * stable, immutable, versioned artifact — WITHOUT a refactor — but is not populated today because
 * all three URLs are auto-updating loaders (a pinned hash would break the load on the provider's
 * next push).
 */
interface ProviderScriptDescriptor {
  src: string;
  /** CORS mode for the request. 'anonymous' => no credentials, non-opaque errors, SRI-ready. */
  crossorigin: 'anonymous' | 'use-credentials';
  /** Referrer policy — 'no-referrer' so the merchant checkout URL is not leaked to the CDN. */
  referrerpolicy: ReferrerPolicy;
  /** Optional SRI hash — only set for an immutable, pinned versioned artifact. Omitted for loaders. */
  integrity?: string;
}

/**
 * GAP-052 posture: all three providers publish AUTO-UPDATING loader entry points (a floating
 * major-version URL the provider re-publishes new bytes under), NOT immutable versioned artifacts.
 * Subresource Integrity (SRI) only works for immutable resources: pinning an `integrity` hash on an
 * auto-updating loader BREAKS the load the moment the provider ships a new build under the same URL,
 * and several BNPL providers explicitly advise against SRI on their loader URLs. So we do NOT add a
 * brittle SRI hash; instead we harden with `crossorigin="anonymous"` + `referrerpolicy` and rely on
 * the merchant page's CSP `script-src` allowlist + PCI DSS 6.4.3 script monitoring for tamper
 * detection. (`integrity` stays available in the descriptor for any provider that later ships a
 * pinnable versioned URL.)
 *
 *  - klarna:   https://x.klarnacdn.net/kp/lib/v1/api.js — `/v1/` is a floating major-version on-demand
 *              loader; Klarna serves updated bytes under it. Posture: crossorigin + no-referrer, NO
 *              SRI. Residual risk mitigated page-side by CSP `script-src x.klarnacdn.net`.
 *  - afterpay: https://js.afterpay.com/afterpay-1.x.js — the literal `-1.x` advertises that it floats
 *              minor/patch within major 1 (the most explicitly auto-updating of the three); an SRI
 *              hash is guaranteed to break on the next 1.x push. Posture: crossorigin + no-referrer,
 *              NO SRI. CSP `script-src js.afterpay.com`.
 *  - affirm:   https://cdn1.affirm.com/js/v2/affirm.js — `/v2/` floating major loader (same shape as
 *              Klarna `/v1/`). Posture: crossorigin + no-referrer, NO SRI. CSP `script-src
 *              cdn1.affirm.com`.
 */
const PROVIDER_SCRIPTS: Record<BnplProvider, ProviderScriptDescriptor> = {
  klarna: {
    src: 'https://x.klarnacdn.net/kp/lib/v1/api.js',
    crossorigin: 'anonymous',
    referrerpolicy: 'no-referrer',
    // integrity intentionally omitted — auto-updating loader; rely on CSP + PCI 6.4.3 monitoring.
  },
  afterpay: {
    src: 'https://js.afterpay.com/afterpay-1.x.js',
    crossorigin: 'anonymous',
    referrerpolicy: 'no-referrer',
    // integrity intentionally omitted — `-1.x` floats within major 1; SRI would break on every push.
  },
  affirm: {
    src: 'https://cdn1.affirm.com/js/v2/affirm.js',
    crossorigin: 'anonymous',
    referrerpolicy: 'no-referrer',
    // integrity intentionally omitted — `/v2/` floating major loader; rely on CSP + PCI 6.4.3.
  },
};

const loadedScripts = new Set<string>();

export class BnplHandler extends EventEmitter<BnplEvents> {
  private config: BnplConfig;
  private scriptLoaded = false;

  constructor(config: BnplConfig) {
    super();
    this.config = config;
  }

  getLabel(): string {
    return PROVIDER_LABELS[this.config.provider] ?? this.config.provider;
  }

  /**
   * Dynamically loads the BNPL provider's SDK script.
   * Only loads when needed (e.g., when the BNPL tab is selected).
   */
  async loadProviderScript(): Promise<void> {
    const descriptor = PROVIDER_SCRIPTS[this.config.provider];
    if (!descriptor || loadedScripts.has(descriptor.src)) {
      this.scriptLoaded = true;
      return;
    }

    return new Promise((resolve, reject) => {
      const script = this.buildProviderScript(descriptor);
      script.onload = () => {
        loadedScripts.add(descriptor.src);
        this.scriptLoaded = true;
        resolve();
      };
      script.onerror = () =>
        reject(new Error(`Failed to load ${this.getLabel()} SDK`));
      document.head.appendChild(script);
    });
  }

  /**
   * GAP-052: builds the loader `<script>` element with the provider's decided supply-chain posture.
   * Exposed as an overridable seam (rather than inlined) so tests can assert the attributes
   * synchronously without depending on the network `onload` firing.
   *
   * Sets `crossOrigin='anonymous'` (CORS request, no credentials, non-opaque errors, SRI-ready) and
   * `referrerPolicy` (default 'no-referrer' — don't leak the checkout URL to the CDN). SRI
   * (`integrity`) is applied ONLY if the descriptor carries a hash; it is intentionally unset for the
   * auto-updating provider loaders (see {@link PROVIDER_SCRIPTS}).
   */
  private buildProviderScript(descriptor: ProviderScriptDescriptor): HTMLScriptElement {
    const script = document.createElement('script');
    script.src = descriptor.src;
    script.async = true;
    script.crossOrigin = descriptor.crossorigin;
    script.referrerPolicy = descriptor.referrerpolicy;
    if (descriptor.integrity) {
      script.integrity = descriptor.integrity;
    }
    return script;
  }

  /**
   * GAP-052 test seam: the frozen provider-script descriptor for the configured provider, so tests
   * can assert the decided per-provider posture (src / crossorigin / referrerpolicy / absence of SRI)
   * without reaching into module internals.
   */
  get __test__(): { descriptor: ProviderScriptDescriptor } {
    return { descriptor: PROVIDER_SCRIPTS[this.config.provider] };
  }

  /**
   * Creates the BNPL messaging/option UI.
   * Shows installment information and a button to proceed.
   */
  createUI(): HTMLElement {
    const container = document.createElement('div');
    container.className = 'BnplOption';
    container.style.padding = '8px 0';

    // Provider info
    const heading = document.createElement('div');
    heading.style.fontWeight = 'var(--nxp-font-weight-bold, 600)';
    heading.style.marginBottom = '8px';
    heading.style.display = 'flex';
    heading.style.alignItems = 'center';
    heading.style.gap = '8px';
    heading.textContent = this.getLabel();
    container.appendChild(heading);

    // Messaging (installment info)
    const messaging = document.createElement('div');
    messaging.style.fontSize = '0.875rem';
    messaging.style.color = 'var(--nxp-color-text, #1A1A2E)';
    messaging.style.opacity = '0.7';
    messaging.style.marginBottom = '12px';

    const installment = (this.config.amount / 400).toFixed(2); // 4 installments

    switch (this.config.provider) {
      case 'klarna':
        messaging.textContent = `Pay in 4 interest-free installments of ${this.config.currency} ${installment}`;
        break;
      case 'afterpay':
        messaging.textContent = `4 interest-free payments of ${this.config.currency} ${installment}`;
        break;
      case 'affirm':
        messaging.textContent = `Pay over time starting at ${this.config.currency} ${installment}/mo`;
        break;
    }

    container.appendChild(messaging);

    // Info text
    const info = document.createElement('p');
    info.style.fontSize = '0.8125rem';
    info.style.color = 'var(--nxp-color-text, #1A1A2E)';
    info.style.opacity = '0.5';
    info.textContent = `You will be redirected to ${this.getLabel()} to complete your purchase.`;
    container.appendChild(info);

    this.emit('ready', undefined);
    return container;
  }

  /**
   * Initiates the BNPL payment flow.
   * Loads the provider script (if needed), tokenizes, confirms, and redirects.
   */
  async startPayment(): Promise<string> {
    const apiBase = this.config.apiBase ?? 'https://api.nexuspay.io';

    try {
      // Load provider SDK on-demand
      if (!this.scriptLoaded) {
        await this.loadProviderScript();
      }

      // Step 1: Tokenize
      const tokenRes = await fetch(`${apiBase}/v1/checkout/tokenize`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(this.config.sessionToken
            ? { Authorization: `Bearer ${this.config.sessionToken}` }
            : {}),
        },
        body: JSON.stringify({
          type: 'bnpl',
          token_data: JSON.stringify({
            provider: this.config.provider,
            locale: this.config.locale ?? 'en-US',
          }),
        }),
      });

      if (!tokenRes.ok) {
        const errData = await tokenRes.json().catch(() => ({}));
        throw new Error(errData?.error?.message ?? 'Tokenization failed');
      }

      const tokenData = await tokenRes.json();

      // Step 2: Confirm
      const confirmRes = await fetch(`${apiBase}/v1/checkout/confirm`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(this.config.sessionToken
            ? { Authorization: `Bearer ${this.config.sessionToken}` }
            : {}),
        },
        body: JSON.stringify({
          payment_token_id: tokenData.id,
          return_url: this.config.returnUrl,
        }),
      });

      if (!confirmRes.ok) {
        const errData = await confirmRes.json().catch(() => ({}));
        throw new Error(errData?.error?.message ?? 'Confirmation failed');
      }

      const confirmData = await confirmRes.json();

      if (confirmData.next_action?.url) {
        this.emit('redirect', { url: confirmData.next_action.url });
        return confirmData.next_action.url;
      }

      throw new Error('No redirect URL received');
    } catch (err) {
      const message = err instanceof Error ? err.message : 'BNPL payment failed';
      this.emit('error', { message });
      throw new Error(message);
    }
  }

  /** Redirects the browser to the provider's page. */
  redirect(url: string): void {
    window.location.href = url;
  }
}
