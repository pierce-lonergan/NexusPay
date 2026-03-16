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

const PROVIDER_SCRIPTS: Record<BnplProvider, string> = {
  klarna: 'https://x.klarnacdn.net/kp/lib/v1/api.js',
  afterpay: 'https://js.afterpay.com/afterpay-1.x.js',
  affirm: 'https://cdn1.affirm.com/js/v2/affirm.js',
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
    const url = PROVIDER_SCRIPTS[this.config.provider];
    if (!url || loadedScripts.has(url)) {
      this.scriptLoaded = true;
      return;
    }

    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = url;
      script.async = true;
      script.onload = () => {
        loadedScripts.add(url);
        this.scriptLoaded = true;
        resolve();
      };
      script.onerror = () =>
        reject(new Error(`Failed to load ${this.getLabel()} SDK`));
      document.head.appendChild(script);
    });
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
