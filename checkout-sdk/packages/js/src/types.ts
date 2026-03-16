/**
 * NexusPay SDK type definitions.
 * @module @nexuspay/js
 */

// --- Appearance & Theming ---

export type ThemePreset = 'default' | 'night' | 'flat' | 'none';

export interface AppearanceVariables {
  colorPrimary?: string;
  colorBackground?: string;
  colorText?: string;
  colorDanger?: string;
  colorSuccess?: string;
  fontFamily?: string;
  fontSizeBase?: string;
  borderRadius?: string;
  spacingUnit?: string;
  fontWeightNormal?: number;
  fontWeightBold?: number;
}

export interface Appearance {
  theme?: ThemePreset;
  variables?: AppearanceVariables;
  /** CSS selector → property map overrides. e.g. { '.Input': { borderColor: '#ccc' } } */
  rules?: Record<string, Record<string, string>>;
}

// --- Options ---

export interface NexusPayOptions {
  apiBase?: string;
  locale?: string;
  appearance?: Appearance;
  debug?: boolean;
}

export interface ElementOptions {
  appearance?: Appearance;
  classes?: Record<string, string>;
}

// --- Payment Session ---

export interface PaymentSessionResult {
  id: string;
  status: 'open' | 'complete' | 'expired';
  amount: number;
  currency: string;
  allowedPaymentMethods: string[];
  paymentIntentId?: string;
}

// --- Tokenization ---

export interface TokenizeResult {
  id: string;
  type: string;
  cardLastFour?: string;
  cardBrand?: string;
  expiresAt: string;
}

// --- Confirm ---

export interface ConfirmResult {
  status: 'succeeded' | 'requires_action' | 'failed';
  paymentIntentId?: string;
  nextAction?: {
    type: 'redirect' | 'three_d_secure';
    url?: string;
  };
  error?: NexusPayError;
}

// --- Payment Method Types ---

export type PaymentMethodType =
  | 'card'
  | 'apple_pay'
  | 'google_pay'
  | 'bank_redirect'
  | 'bnpl';

// --- Errors ---

export interface NexusPayError {
  type: 'authentication_error' | 'api_error' | 'validation_error' | 'network_error' | 'session_error' | 'rate_limit_error';
  code: string;
  message: string;
}

// --- Card Brands ---

export type CardBrand =
  | 'visa'
  | 'mastercard'
  | 'amex'
  | 'discover'
  | 'diners'
  | 'jcb'
  | 'unionpay'
  | 'maestro'
  | 'unknown';

// --- Events ---

export type NexusPayEventType =
  | 'ready'
  | 'error'
  | 'payment_complete'
  | 'payment_failed'
  | 'session_expired';

export interface NexusPayEvent<T = unknown> {
  type: NexusPayEventType;
  data?: T;
}
