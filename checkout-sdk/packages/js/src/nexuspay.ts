/**
 * NexusPay SDK entry point.
 * Creates a NexusPay instance for payment session management,
 * tokenization, and payment confirmation.
 */

import type {
  NexusPayOptions,
  PaymentSessionResult,
  TokenizeResult,
  ConfirmResult,
  NexusPayError,
} from './types';
import { HttpClient } from './http-client';
import { EventEmitter } from './event-emitter';
import { setDebug, debug } from './utils/logger';

const DEFAULT_API_BASE = 'https://api.nexuspay.io';

interface NexusPayEvents {
  ready: PaymentSessionResult;
  error: NexusPayError;
  payment_complete: ConfirmResult;
  payment_failed: NexusPayError;
  session_expired: void;
}

export class NexusPay extends EventEmitter<NexusPayEvents> {
  private readonly publishableKey: string;
  private readonly httpClient: HttpClient;
  private session: PaymentSessionResult | null = null;

  constructor(publishableKey: string, options?: NexusPayOptions) {
    super();
    this.publishableKey = publishableKey;

    if (options?.debug) {
      setDebug(true);
    }

    this.httpClient = new HttpClient({
      baseUrl: options?.apiBase ?? DEFAULT_API_BASE,
      timeout: 10_000,
    });

    debug('NexusPay initialized', { publishableKey: publishableKey.substring(0, 8) + '...' });
  }

  /**
   * Loads the payment session and authenticates the SDK.
   * Must be called before tokenize() or confirm().
   */
  async loadSession(clientSecret: string): Promise<PaymentSessionResult> {
    debug('Loading session...');

    // The client_secret IS the session JWT
    this.httpClient.setSessionToken(clientSecret);

    try {
      const session = await this.httpClient.get<PaymentSessionResult>('/v1/checkout/session');
      this.session = session;
      this.emit('ready', session);
      debug('Session loaded', session);
      return session;
    } catch (err) {
      const error = err as NexusPayError;
      this.emit('error', error);
      throw error;
    }
  }

  /**
   * Tokenizes a payment method.
   * Card data is encrypted in the PCI iframe before being sent.
   */
  async tokenize(data: {
    type: string;
    tokenData?: string;
    cardLastFour?: string;
    cardBrand?: string;
    cardExpMonth?: number;
    cardExpYear?: number;
  }): Promise<TokenizeResult> {
    this.ensureSession();
    debug('Tokenizing payment method', { type: data.type });

    try {
      return await this.httpClient.post<TokenizeResult>('/v1/checkout/tokenize', {
        type: data.type,
        token_data: data.tokenData,
        card_last_four: data.cardLastFour,
        card_brand: data.cardBrand,
        card_exp_month: data.cardExpMonth,
        card_exp_year: data.cardExpYear,
      });
    } catch (err) {
      const error = err as NexusPayError;
      if (error.code === 'session_expired') {
        this.emit('session_expired', undefined);
      }
      throw error;
    }
  }

  /**
   * Confirms the payment using a tokenized payment method.
   */
  async confirm(paymentTokenId: string): Promise<ConfirmResult> {
    this.ensureSession();
    debug('Confirming payment', { paymentTokenId });

    try {
      const result = await this.httpClient.post<ConfirmResult>('/v1/checkout/confirm', {
        payment_token_id: paymentTokenId,
      });

      if (result.status === 'succeeded') {
        this.emit('payment_complete', result);
      } else if (result.status === 'failed') {
        const error: NexusPayError = result.error ?? {
          type: 'api_error',
          code: 'payment_failed',
          message: 'Payment failed',
        };
        this.emit('payment_failed', error);
      }

      return result;
    } catch (err) {
      const error = err as NexusPayError;
      this.emit('payment_failed', error);
      throw error;
    }
  }

  getSession(): PaymentSessionResult | null {
    return this.session;
  }

  private ensureSession(): void {
    if (!this.session) {
      throw {
        type: 'session_error',
        code: 'no_session',
        message: 'Call loadSession() before tokenize() or confirm()',
      } satisfies NexusPayError;
    }
  }
}
