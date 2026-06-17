/**
 * @nexus-pay/js — PCI-compliant card tokenization and payment elements.
 * @module @nexus-pay/js
 */

// Core
export { NexusPay } from './nexuspay';

// Types
export type {
  Appearance,
  AppearanceVariables,
  ThemePreset,
  NexusPayOptions,
  ElementOptions,
  PaymentSessionResult,
  TokenizeResult,
  ConfirmResult,
  PaymentMethodType,
  NexusPayError,
  CardBrand,
  NexusPayEventType,
  NexusPayEvent,
} from './types';

// Theme
export { resolveVariables, generateCSSVariables, injectThemeStyles } from './theme/css-properties';
export { PRESETS } from './theme/presets';
export { DEFAULT_VARIABLES, NIGHT_VARIABLES, FLAT_VARIABLES, ANIMATION, BREAKPOINTS } from './theme/tokens';

// Elements
export { CardElement, type CardElementEvents, type CardElementOptions } from './elements/card-element';
export { PaymentElement, type PaymentElementEvents, type PaymentElementOptions } from './elements/payment-element';
export { AddressElement, type AddressData, type AddressElementEvents, type AddressElementOptions } from './elements/address-element';
export { IframeManager, type IframeManagerOptions, type CardChangePayload, type TokenizeResponsePayload } from './elements/iframe-manager';
export { BaseElement } from './elements/base-element';
export { CARD_BRAND_ICONS } from './elements/icons';

// APM (Alternative Payment Methods)
export { ApplePayHandler, type ApplePayConfig, type ApplePayTokenResult } from './apm/apple-pay';
export { GooglePayHandler, type GooglePayConfig, type GooglePayTokenResult } from './apm/google-pay';
export { BankRedirectHandler, type BankRedirectConfig, type BankRedirectProvider, type BankOption } from './apm/bank-redirect';
export { BnplHandler, type BnplConfig, type BnplProvider, type BnplTokenResult } from './apm/bnpl';
export { wrapWalletButton, createDivider } from './apm/wallet-button';

// 3DS
export { ChallengeHandler, type ThreeDSResult, type NextAction } from './three-ds/challenge-handler';
export { FingerprintHandler, type FingerprintResult } from './three-ds/fingerprint-handler';

// Utilities
export { detectBrand, luhnCheck, isExpiryValid, formatPAN, cvcLength, maxPanLength } from './utils/card-validator';
export { EventEmitter } from './event-emitter';
export { HttpClient } from './http-client';
