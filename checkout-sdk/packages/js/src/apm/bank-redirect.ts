/**
 * BankRedirectHandler — supports iDEAL, Bancontact, Giropay, Przelewy24.
 * Renders bank selector UI, tokenizes on selection, confirms, then redirects
 * to the bank's authentication page. Handles return URL callback.
 */

import { EventEmitter } from '../event-emitter';

interface BankRedirectEvents {
  [key: string]: unknown;
  ready: void;
  selected: { bankId: string; bankName: string };
  redirect: { url: string };
  error: { message: string };
}

export type BankRedirectProvider = 'ideal' | 'bancontact' | 'giropay' | 'p24';

export interface BankOption {
  id: string;
  name: string;
  icon?: string;
}

export interface BankRedirectConfig {
  provider: BankRedirectProvider;
  apiBase?: string;
  sessionToken?: string;
  returnUrl: string;
}

const PROVIDER_LABELS: Record<BankRedirectProvider, string> = {
  ideal: 'iDEAL',
  bancontact: 'Bancontact',
  giropay: 'Giropay',
  p24: 'Przelewy24',
};

/** Common iDEAL banks. */
const IDEAL_BANKS: BankOption[] = [
  { id: 'abn_amro', name: 'ABN AMRO' },
  { id: 'asn_bank', name: 'ASN Bank' },
  { id: 'bunq', name: 'bunq' },
  { id: 'ing', name: 'ING' },
  { id: 'knab', name: 'Knab' },
  { id: 'rabobank', name: 'Rabobank' },
  { id: 'regiobank', name: 'RegioBank' },
  { id: 'revolut', name: 'Revolut' },
  { id: 'sns_bank', name: 'SNS Bank' },
  { id: 'triodos_bank', name: 'Triodos Bank' },
  { id: 'van_lanschot', name: 'Van Lanschot Kempen' },
];

export class BankRedirectHandler extends EventEmitter<BankRedirectEvents> {
  private config: BankRedirectConfig;
  private selectedBank: BankOption | null = null;

  constructor(config: BankRedirectConfig) {
    super();
    this.config = config;
  }

  /** Gets available banks for the current provider. */
  getBanks(): BankOption[] {
    switch (this.config.provider) {
      case 'ideal':
        return IDEAL_BANKS;
      case 'bancontact':
      case 'giropay':
      case 'p24':
        // These providers handle bank selection on their own page
        return [];
      default:
        return [];
    }
  }

  /** Gets the display label for the current provider. */
  getLabel(): string {
    return PROVIDER_LABELS[this.config.provider] ?? this.config.provider;
  }

  /** Whether the provider requires the user to select a bank in our UI. */
  requiresBankSelection(): boolean {
    return this.config.provider === 'ideal';
  }

  /**
   * Creates the bank selector UI element.
   * For iDEAL, renders a dropdown of Dutch banks.
   * For other providers, renders a simple "Continue" button.
   */
  createUI(): HTMLElement {
    const container = document.createElement('div');
    container.className = 'BankRedirect';
    container.style.padding = '8px 0';

    if (this.requiresBankSelection()) {
      const banks = this.getBanks();

      const label = document.createElement('label');
      label.className = 'Label';
      label.textContent = `Select your bank`;
      label.style.display = 'block';
      label.style.marginBottom = '8px';
      container.appendChild(label);

      const select = document.createElement('select');
      select.className = 'Input';
      select.style.width = '100%';

      const placeholder = document.createElement('option');
      placeholder.value = '';
      placeholder.textContent = 'Choose a bank...';
      placeholder.disabled = true;
      placeholder.selected = true;
      select.appendChild(placeholder);

      for (const bank of banks) {
        const option = document.createElement('option');
        option.value = bank.id;
        option.textContent = bank.name;
        select.appendChild(option);
      }

      select.addEventListener('change', () => {
        const bank = banks.find((b) => b.id === select.value);
        if (bank) {
          this.selectedBank = bank;
          this.emit('selected', { bankId: bank.id, bankName: bank.name });
        }
      });

      container.appendChild(select);
    } else {
      const info = document.createElement('p');
      info.style.fontSize = '0.875rem';
      info.style.color = 'var(--nxp-color-text, #1A1A2E)';
      info.style.opacity = '0.7';
      info.style.margin = '8px 0';
      info.textContent = `You will be redirected to ${this.getLabel()} to complete your payment.`;
      container.appendChild(info);
    }

    this.emit('ready', undefined);
    return container;
  }

  /**
   * Initiates the bank redirect payment flow.
   * Tokenizes the selection, confirms, and returns the redirect URL.
   */
  async startPayment(): Promise<string> {
    if (this.requiresBankSelection() && !this.selectedBank) {
      const error = 'Please select a bank';
      this.emit('error', { message: error });
      throw new Error(error);
    }

    const apiBase = this.config.apiBase ?? 'https://api.nexuspay.io';

    try {
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
          type: 'bank_redirect',
          token_data: JSON.stringify({
            provider: this.config.provider,
            bank_id: this.selectedBank?.id,
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
      const message = err instanceof Error ? err.message : 'Bank redirect failed';
      this.emit('error', { message });
      throw new Error(message);
    }
  }

  /** Redirects the browser to the bank's authentication page. */
  redirect(url: string): void {
    window.location.href = url;
  }

  getSelectedBank(): BankOption | null {
    return this.selectedBank;
  }
}
