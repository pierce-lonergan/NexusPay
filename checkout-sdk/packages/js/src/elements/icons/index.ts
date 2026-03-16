/**
 * Inline SVG icons for card brands, payment methods, and UI.
 * All icons use `currentColor` for theme adaptation.
 */

// --- Card Brand Icons ---

export const ICON_VISA = `<svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect width="32" height="20" rx="2" fill="#1A1F71"/>
  <path d="M13.2 13.5L14.7 6.5H16.8L15.3 13.5H13.2Z" fill="white"/>
  <path d="M21.8 6.7C21.4 6.5 20.7 6.3 19.9 6.3C17.8 6.3 16.3 7.4 16.3 9C16.3 10.2 17.4 10.8 18.2 11.2C19 11.6 19.3 11.8 19.3 12.2C19.3 12.7 18.7 13 18.1 13C17.3 13 16.8 12.9 16.1 12.5L15.8 12.4L15.5 14.1C16 14.3 16.9 14.5 17.9 14.5C20.1 14.5 21.6 13.4 21.6 11.7C21.6 10.8 21 10.1 19.7 9.5C19 9.2 18.5 8.9 18.5 8.5C18.5 8.2 18.9 7.8 19.7 7.8C20.4 7.8 20.9 7.9 21.3 8.1L21.5 8.2L21.8 6.7Z" fill="white"/>
  <path d="M24.2 6.5H22.6C22.1 6.5 21.7 6.6 21.5 7.2L18.5 13.5H20.7L21.1 12.3H23.8L24 13.5H26L24.2 6.5ZM21.8 10.7L22.7 8.3L23.2 10.7H21.8Z" fill="white"/>
  <path d="M12.1 6.5L10 11.1L9.8 10L9.1 7.2C9 6.7 8.6 6.5 8.1 6.5H5.1L5 6.7C5.8 6.9 6.5 7.2 7.1 7.5L9 13.5H11.2L14.3 6.5H12.1Z" fill="white"/>
</svg>`;

export const ICON_MASTERCARD = `<svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect width="32" height="20" rx="2" fill="#252525"/>
  <circle cx="12" cy="10" r="6" fill="#EB001B"/>
  <circle cx="20" cy="10" r="6" fill="#F79E1B"/>
  <path d="M16 5.4C17.5 6.5 18.5 8.1 18.5 10C18.5 11.9 17.5 13.5 16 14.6C14.5 13.5 13.5 11.9 13.5 10C13.5 8.1 14.5 6.5 16 5.4Z" fill="#FF5F00"/>
</svg>`;

export const ICON_AMEX = `<svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect width="32" height="20" rx="2" fill="#2E77BC"/>
  <path d="M5 13.5V6.5H8.5L9.3 8.5L10.1 6.5H13.5V13.5H11.5V8.5L10.5 11H8L7 8.5V13.5H5Z" fill="white"/>
  <path d="M14 13.5V6.5H19.5L20 7.8H16V9.3H19.3V10.5H16V12.2H20L19.5 13.5H14Z" fill="white"/>
  <path d="M20.5 13.5L23.5 9.8L20.5 6.5H23L24.5 8.5L26 6.5H28.5L25.5 9.8L28.5 13.5H26L24.5 11.2L23 13.5H20.5Z" fill="white"/>
</svg>`;

export const ICON_DISCOVER = `<svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect width="32" height="20" rx="2" fill="#fff" stroke="#E5E7EB"/>
  <path d="M0 10C0 10 8 4 32 4V20H0V10Z" fill="#F48120" opacity="0.15"/>
  <circle cx="20" cy="10" r="4" fill="#F48120"/>
  <text x="5" y="12" font-size="5" font-weight="bold" fill="#1A1A2E" font-family="system-ui">DISCOVER</text>
</svg>`;

export const ICON_JCB = `<svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect width="32" height="20" rx="2" fill="#fff" stroke="#E5E7EB"/>
  <rect x="5" y="3" width="7" height="14" rx="2" fill="#0E4C96"/>
  <rect x="12.5" y="3" width="7" height="14" rx="2" fill="#E0292D"/>
  <rect x="20" y="3" width="7" height="14" rx="2" fill="#1BA23E"/>
  <text x="7" y="12" font-size="4" font-weight="bold" fill="white" font-family="system-ui">J</text>
  <text x="14.5" y="12" font-size="4" font-weight="bold" fill="white" font-family="system-ui">C</text>
  <text x="22" y="12" font-size="4" font-weight="bold" fill="white" font-family="system-ui">B</text>
</svg>`;

export const ICON_UNIONPAY = `<svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect width="32" height="20" rx="2" fill="#fff" stroke="#E5E7EB"/>
  <path d="M3 2H12C13 2 14 3 13.5 4L10.5 18H3C2 18 1 17 1.5 16L4.5 2H3Z" fill="#E21836"/>
  <path d="M11 2H21C22 2 23 3 22.5 4L19.5 18H11C10 18 9 17 9.5 16L12.5 2H11Z" fill="#00447C"/>
  <path d="M19 2H29C30 2 31 3 30.5 4L27.5 18H19C18 18 17 17 17.5 16L20.5 2H19Z" fill="#007B84"/>
</svg>`;

export const ICON_MAESTRO = `<svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect width="32" height="20" rx="2" fill="#fff" stroke="#E5E7EB"/>
  <circle cx="12" cy="10" r="6" fill="#0099DF"/>
  <circle cx="20" cy="10" r="6" fill="#000"/>
  <path d="M16 5.4C17.5 6.5 18.5 8.1 18.5 10C18.5 11.9 17.5 13.5 16 14.6C14.5 13.5 13.5 11.9 13.5 10C13.5 8.1 14.5 6.5 16 5.4Z" fill="#6C6BBD"/>
</svg>`;

export const ICON_DINERS = `<svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect width="32" height="20" rx="2" fill="#fff" stroke="#E5E7EB"/>
  <circle cx="14" cy="10" r="7" fill="#0079BE"/>
  <path d="M12 4.5V15.5C9.5 14.5 8 12.5 8 10C8 7.5 9.5 5.5 12 4.5Z" fill="white"/>
  <path d="M16 4.5V15.5C18.5 14.5 20 12.5 20 10C20 7.5 18.5 5.5 16 4.5Z" fill="white"/>
</svg>`;

export const ICON_GENERIC_CARD = `<svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect x="0.5" y="0.5" width="31" height="19" rx="1.5" stroke="currentColor" opacity="0.3"/>
  <rect x="4" y="4" width="8" height="5" rx="1" fill="currentColor" opacity="0.2"/>
  <line x1="4" y1="13" x2="12" y2="13" stroke="currentColor" opacity="0.2" stroke-width="1.5"/>
  <line x1="4" y1="16" x2="8" y2="16" stroke="currentColor" opacity="0.15" stroke-width="1.5"/>
</svg>`;

// --- Payment Method Icons ---

export const ICON_APPLE_PAY = `<svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect width="32" height="20" rx="2" fill="#000"/>
  <path d="M9.2 6.5C9.6 6 9.9 5.3 9.8 4.6C9.2 4.6 8.5 5 8.1 5.5C7.7 5.9 7.4 6.6 7.5 7.3C8.1 7.3 8.8 7 9.2 6.5Z" fill="white"/>
  <path d="M9.8 7.4C8.9 7.4 8.2 7.9 7.7 7.9C7.2 7.9 6.6 7.4 5.8 7.5C4.9 7.5 4.1 8 3.6 8.8C2.6 10.3 3.4 12.6 4.3 13.8C4.8 14.4 5.4 15.1 6.1 15.1C6.8 15.1 7.1 14.7 7.9 14.7C8.8 14.7 9 15.1 9.8 15.1C10.5 15.1 11 14.4 11.5 13.8C12 13.1 12.3 12.4 12.3 12.4C12.3 12.4 11 11.9 11 10.4C11 9.1 12 8.5 12.1 8.4C11.4 7.5 10.4 7.4 9.8 7.4Z" fill="white"/>
  <text x="13.5" y="13" font-size="6" font-weight="600" fill="white" font-family="system-ui">Pay</text>
</svg>`;

export const ICON_GOOGLE_PAY = `<svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect x="0.5" y="0.5" width="31" height="19" rx="1.5" stroke="#E5E7EB" fill="#fff"/>
  <text x="5" y="13" font-size="5" font-weight="500" fill="#5F6368" font-family="system-ui">G Pay</text>
</svg>`;

export const ICON_BANK = `<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
  <path d="M12 2L2 7V9H22V7L12 2Z" stroke="currentColor" stroke-width="1.5" fill="none"/>
  <path d="M4 9V17M8 9V17M12 9V17M16 9V17M20 9V17" stroke="currentColor" stroke-width="1.5"/>
  <path d="M2 17H22V19H2V17Z" stroke="currentColor" stroke-width="1.5" fill="none"/>
</svg>`;

export const ICON_BNPL = `<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect x="2" y="4" width="20" height="16" rx="2" stroke="currentColor" stroke-width="1.5"/>
  <path d="M7 10H17M7 14H12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
  <path d="M16 12.5L17.5 14L20 11" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
</svg>`;

// --- UI Icons ---

export const ICON_LOCK = `<svg viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect x="3" y="7" width="10" height="7" rx="1.5" stroke="currentColor" stroke-width="1.5"/>
  <path d="M5 7V5C5 3.34 6.34 2 8 2C9.66 2 11 3.34 11 5V7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
  <circle cx="8" cy="11" r="1" fill="currentColor"/>
</svg>`;

export const ICON_CHECKMARK = `<svg viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
  <path d="M3 8L6.5 11.5L13 5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
</svg>`;

export const ICON_ERROR = `<svg viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
  <circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.5"/>
  <path d="M8 5V9" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
  <circle cx="8" cy="11.5" r="0.75" fill="currentColor"/>
</svg>`;

export const ICON_CARET = `<svg viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
  <path d="M4 6L8 10L12 6" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
</svg>`;

// --- Brand map ---

import type { CardBrand } from '../../types';

export const CARD_BRAND_ICONS: Record<CardBrand, string> = {
  visa: ICON_VISA,
  mastercard: ICON_MASTERCARD,
  amex: ICON_AMEX,
  discover: ICON_DISCOVER,
  jcb: ICON_JCB,
  unionpay: ICON_UNIONPAY,
  maestro: ICON_MAESTRO,
  diners: ICON_DINERS,
  unknown: ICON_GENERIC_CARD,
};
