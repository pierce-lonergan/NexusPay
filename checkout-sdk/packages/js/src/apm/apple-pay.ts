/**
 * ApplePayHandler — Apple Pay integration via the Apple Pay JS API.
 * Feature detection via `ApplePaySession.canMakePayments()`,
 * session creation, merchant validation, and token mapping.
 * Button follows official Apple brand guidelines.
 */

import { EventEmitter } from '../event-emitter';

interface ApplePayEvents {
  [key: string]: unknown;
  ready: void;
  token: ApplePayTokenResult;
  cancel: void;
  error: { message: string };
}

export interface ApplePayTokenResult {
  type: 'apple_pay';
  tokenData: string;
  displayName?: string;
}

export interface ApplePayConfig {
  merchantId: string;
  merchantName: string;
  countryCode: string;
  currencyCode: string;
  amount: number;
  apiBase?: string;
  sessionToken?: string;
}

export class ApplePayHandler extends EventEmitter<ApplePayEvents> {
  private config: ApplePayConfig;
  private available = false;

  constructor(config: ApplePayConfig) {
    super();
    this.config = config;
  }

  /**
   * Checks if Apple Pay is available on this device/browser.
   */
  async checkAvailability(): Promise<boolean> {
    try {
      if (
        typeof window === 'undefined' ||
        !(window as any).ApplePaySession
      ) {
        this.available = false;
        return false;
      }

      const canMake = (window as any).ApplePaySession.canMakePayments();
      this.available = !!canMake;

      if (this.available) {
        this.emit('ready', undefined);
      }

      return this.available;
    } catch {
      this.available = false;
      return false;
    }
  }

  isAvailable(): boolean {
    return this.available;
  }

  /**
   * Creates the Apple Pay button following official Apple brand guidelines.
   * Returns an HTMLElement to be appended to the DOM.
   */
  createButton(options?: {
    type?: 'plain' | 'buy' | 'donate' | 'check-out' | 'book' | 'subscribe';
    style?: 'black' | 'white' | 'white-outline';
    locale?: string;
  }): HTMLElement {
    const button = document.createElement('div');
    const type = options?.type ?? 'plain';
    const style = options?.style ?? 'black';
    button.setAttribute('role', 'button');
    button.setAttribute('aria-label', 'Pay with Apple Pay');
    button.setAttribute('tabindex', '0');

    Object.assign(button.style, {
      display: 'inline-block',
      width: '100%',
      minHeight: '44px',
      maxHeight: '64px',
      borderRadius: '8px',
      cursor: 'pointer',
      WebkitAppearance: '-apple-pay-button',
      appearance: '-apple-pay-button' as string,
    });

    // Apple Pay CSS properties
    (button.style as any)['-apple-pay-button-type'] = type;
    (button.style as any)['-apple-pay-button-style'] = style;

    // Fallback for browsers without native Apple Pay button rendering
    if (typeof CSS === 'undefined' || !CSS.supports?.('-webkit-appearance', '-apple-pay-button')) {
      const bgColor = style === 'black' ? '#000' : '#fff';
      const textColor = style === 'black' ? '#fff' : '#000';
      const border = style === 'white-outline' ? '1px solid #000' : 'none';

      Object.assign(button.style, {
        backgroundColor: bgColor,
        color: textColor,
        border: border,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontFamily: '-apple-system, BlinkMacSystemFont, sans-serif',
        fontSize: '16px',
        fontWeight: '500',
        padding: '12px 24px',
      });

      button.innerHTML = `<span style="margin-right:6px">&#63743;</span> Pay`;
    }

    button.addEventListener('click', () => this.startPayment());
    button.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        this.startPayment();
      }
    });

    return button;
  }

  /**
   * Initiates the Apple Pay payment session.
   */
  async startPayment(): Promise<void> {
    if (!this.available) {
      this.emit('error', { message: 'Apple Pay is not available' });
      return;
    }

    try {
      const ApplePaySession = (window as any).ApplePaySession;

      const paymentRequest = {
        countryCode: this.config.countryCode,
        currencyCode: this.config.currencyCode,
        merchantCapabilities: ['supports3DS', 'supportsDebit', 'supportsCredit'],
        supportedNetworks: ['visa', 'masterCard', 'amex', 'discover'],
        total: {
          label: this.config.merchantName,
          amount: (this.config.amount / 100).toFixed(2), // minor → major units
          type: 'final',
        },
      };

      const session = new ApplePaySession(6, paymentRequest);

      session.onvalidatemerchant = async (event: any) => {
        try {
          const apiBase = this.config.apiBase ?? 'https://api.nexuspay.io';
          const response = await fetch(`${apiBase}/v1/checkout/apple-pay/validate`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              ...(this.config.sessionToken
                ? { Authorization: `Bearer ${this.config.sessionToken}` }
                : {}),
            },
            body: JSON.stringify({
              validationURL: event.validationURL,
              merchantId: this.config.merchantId,
              displayName: this.config.merchantName,
              domainName: window.location.hostname,
            }),
          });

          const merchantSession = await response.json();
          session.completeMerchantValidation(merchantSession);
        } catch (err) {
          session.abort();
          this.emit('error', {
            message: err instanceof Error ? err.message : 'Merchant validation failed',
          });
        }
      };

      session.onpaymentauthorized = (event: any) => {
        const token = event.payment?.token;
        if (token) {
          this.emit('token', {
            type: 'apple_pay',
            tokenData: JSON.stringify(token.paymentData),
            displayName: token.paymentMethod?.displayName,
          });

          session.completePayment({
            status: ApplePaySession.STATUS_SUCCESS,
          });
        } else {
          session.completePayment({
            status: ApplePaySession.STATUS_FAILURE,
          });
          this.emit('error', { message: 'No payment token received' });
        }
      };

      session.oncancel = () => {
        this.emit('cancel', undefined);
      };

      session.begin();
    } catch (err) {
      this.emit('error', {
        message: err instanceof Error ? err.message : 'Failed to start Apple Pay',
      });
    }
  }
}
