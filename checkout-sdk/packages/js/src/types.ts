/**
 * NexusPay SDK type definitions.
 * @module @nexus-pay/js
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
  // --- P2: extended Stripe-class token set ---
  /** Helper / secure-line / powered-by / placeholder ink. */
  colorTextSecondary?: string;
  /** Placeholder text inside inputs (promotes the hard-coded #9CA3AF). */
  colorTextPlaceholder?: string;
  /** Resting 1px field hairline (promotes INPUT_STATES.borderDefault to a real token). */
  colorBorder?: string;
  /** Field hairline on hover. */
  colorBorderHover?: string;
  /** Warning / caution accent. */
  colorWarning?: string;
  /** Card surface — may differ from the page colorBackground (enables the floating card). */
  colorSurface?: string;
  /** Button label / on-accent foreground color. */
  onPrimary?: string;
  /** Mid weight for labels and buttons (currently only normal/bold exist). */
  fontWeightMedium?: number;
  /** Pay-button radius; falls back to borderRadius when unset. */
  buttonBorderRadius?: string;
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
  status: 'succeeded' | 'processing' | 'requires_action' | 'failed';
  /** Gateway payment id (INT-6): `pay_test_*` in test mode, opaque connector id in live. */
  paymentId?: string;
  /** Key mode that produced this payment (INT-3): server-derived, never client-set. */
  mode?: 'test' | 'live';
  /** `true` when `mode === 'live'` (INT-3); mirrors the webhook envelope's livemode flag. */
  livemode?: boolean;
  nextAction?: {
    type: 'redirect' | 'three_d_secure';
    url?: string;
  };
  error?: NexusPayError;
  /** @deprecated kept for back-compat; the server now sends `paymentId`. */
  paymentIntentId?: string;
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
  // INT-6: `payment_error` matches the server confirm error envelope ({ type: 'payment_error', ... }),
  // so a `ConfirmResult.error` (a NexusPayError) can carry the gateway's failure type verbatim.
  type:
    | 'authentication_error'
    | 'api_error'
    | 'validation_error'
    | 'network_error'
    | 'session_error'
    | 'rate_limit_error'
    | 'payment_error';
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
