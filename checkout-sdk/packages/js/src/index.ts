/**
 * @nexuspay/js — PCI-compliant card tokenization and payment elements.
 * @module @nexuspay/js
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

// Utilities
export { detectBrand, luhnCheck, isExpiryValid, formatPAN, cvcLength, maxPanLength } from './utils/card-validator';
export { EventEmitter } from './event-emitter';
export { HttpClient } from './http-client';
