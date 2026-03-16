/**
 * GooglePayHandler — Google Pay integration via the Google Pay API.
 * Loads the Google Pay JS API, checks readiness, creates button
 * following official Google brand guidelines, and handles token extraction.
 */

import { EventEmitter } from '../event-emitter';

interface GooglePayEvents {
  [key: string]: unknown;
  ready: void;
  token: GooglePayTokenResult;
  cancel: void;
  error: { message: string };
}

export interface GooglePayTokenResult {
  type: 'google_pay';
  tokenData: string;
  cardNetwork?: string;
  cardDetails?: string;
}

export interface GooglePayConfig {
  merchantId: string;
  merchantName: string;
  gatewayId: string;
  countryCode: string;
  currencyCode: string;
  amount: number;
  environment?: 'TEST' | 'PRODUCTION';
}

const GOOGLE_PAY_SCRIPT_URL = 'https://pay.google.com/gp/p/js/pay.js';

const BASE_REQUEST = {
  apiVersion: 2,
  apiVersionMinor: 0,
};

const ALLOWED_CARD_NETWORKS = ['VISA', 'MASTERCARD', 'AMEX', 'DISCOVER', 'JCB'];
const ALLOWED_AUTH_METHODS = ['PAN_ONLY', 'CRYPTOGRAM_3DS'];

export class GooglePayHandler extends EventEmitter<GooglePayEvents> {
  private config: GooglePayConfig;
  private paymentsClient: any = null;
  private available = false;
  private scriptLoaded = false;

  constructor(config: GooglePayConfig) {
    super();
    this.config = config;
  }

  /**
   * Loads the Google Pay API script and checks if the device supports Google Pay.
   */
  async checkAvailability(): Promise<boolean> {
    try {
      await this.loadScript();

      const google = (window as any).google;
      if (!google?.payments?.api?.PaymentsClient) {
        this.available = false;
        return false;
      }

      this.paymentsClient = new google.payments.api.PaymentsClient({
        environment: this.config.environment ?? 'TEST',
      });

      const response = await this.paymentsClient.isReadyToPay({
        ...BASE_REQUEST,
        allowedPaymentMethods: [this.getBaseCardPaymentMethod()],
      });

      this.available = !!response?.result;

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
   * Creates the Google Pay button following official Google brand guidelines.
   * Returns an HTMLElement to be appended to the DOM.
   */
  createButton(options?: {
    type?: 'buy' | 'checkout' | 'donate' | 'order' | 'pay' | 'plain' | 'subscribe';
    color?: 'default' | 'black' | 'white';
    locale?: string;
    sizeMode?: 'static' | 'fill';
  }): HTMLElement {
    if (!this.paymentsClient) {
      const fallback = document.createElement('div');
      fallback.textContent = 'Google Pay unavailable';
      return fallback;
    }

    try {
      const button = this.paymentsClient.createButton({
        onClick: () => this.startPayment(),
        buttonType: options?.type ?? 'pay',
        buttonColor: options?.color ?? 'default',
        buttonLocale: options?.locale,
        buttonSizeMode: options?.sizeMode ?? 'fill',
      });

      // Ensure minimum touch target
      if (button instanceof HTMLElement) {
        button.style.minHeight = '44px';
        button.style.width = '100%';
        button.style.borderRadius = '8px';
      }

      return button;
    } catch {
      // Fallback button
      const button = document.createElement('button');
      button.type = 'button';
      Object.assign(button.style, {
        width: '100%',
        minHeight: '44px',
        backgroundColor: '#000',
        color: '#fff',
        border: 'none',
        borderRadius: '8px',
        fontSize: '16px',
        fontWeight: '500',
        cursor: 'pointer',
        fontFamily: "'Google Sans', Roboto, sans-serif",
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: '8px',
      });

      button.innerHTML = '<span>G</span> Pay';
      button.addEventListener('click', () => this.startPayment());
      return button;
    }
  }

  /**
   * Initiates the Google Pay payment flow.
   */
  async startPayment(): Promise<void> {
    if (!this.paymentsClient || !this.available) {
      this.emit('error', { message: 'Google Pay is not available' });
      return;
    }

    try {
      const paymentDataRequest = {
        ...BASE_REQUEST,
        allowedPaymentMethods: [this.getCardPaymentMethod()],
        transactionInfo: {
          totalPriceStatus: 'FINAL',
          totalPrice: (this.config.amount / 100).toFixed(2),
          currencyCode: this.config.currencyCode,
          countryCode: this.config.countryCode,
        },
        merchantInfo: {
          merchantId: this.config.merchantId,
          merchantName: this.config.merchantName,
        },
      };

      const paymentData = await this.paymentsClient.loadPaymentData(
        paymentDataRequest,
      );

      const paymentMethodData = paymentData?.paymentMethodData;
      if (paymentMethodData?.tokenizationData?.token) {
        this.emit('token', {
          type: 'google_pay',
          tokenData: paymentMethodData.tokenizationData.token,
          cardNetwork: paymentMethodData.info?.cardNetwork,
          cardDetails: paymentMethodData.info?.cardDetails,
        });
      } else {
        this.emit('error', { message: 'No payment token received from Google Pay' });
      }
    } catch (err: any) {
      if (err?.statusCode === 'CANCELED') {
        this.emit('cancel', undefined);
      } else {
        this.emit('error', {
          message: err instanceof Error ? err.message : 'Google Pay payment failed',
        });
      }
    }
  }

  private getBaseCardPaymentMethod() {
    return {
      type: 'CARD',
      parameters: {
        allowedAuthMethods: ALLOWED_AUTH_METHODS,
        allowedCardNetworks: ALLOWED_CARD_NETWORKS,
      },
    };
  }

  private getCardPaymentMethod() {
    return {
      ...this.getBaseCardPaymentMethod(),
      tokenizationSpecification: {
        type: 'PAYMENT_GATEWAY',
        parameters: {
          gateway: 'nexuspay',
          gatewayMerchantId: this.config.gatewayId,
        },
      },
    };
  }

  private loadScript(): Promise<void> {
    if (this.scriptLoaded || (window as any).google?.payments?.api) {
      return Promise.resolve();
    }

    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = GOOGLE_PAY_SCRIPT_URL;
      script.async = true;

      const timeout = setTimeout(() => {
        reject(new Error('Google Pay script load timed out'));
      }, 5_000);

      script.onload = () => {
        clearTimeout(timeout);
        this.scriptLoaded = true;
        resolve();
      };
      script.onerror = () => {
        clearTimeout(timeout);
        reject(new Error('Failed to load Google Pay script'));
      };
      document.head.appendChild(script);
    });
  }
}
