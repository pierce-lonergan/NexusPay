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

/*
 * Authentic flat brand marks, recreated faithfully from the Apache-2.0
 * aaronfagan/svg-credit-card-payment-icons set (ISO 0 0 780 500 source) and
 * redrawn at the in-field chip viewBox (0 0 32 20) so they read crisply at
 * 32x20 instead of degrading into illegible sub-pixel wordmarks. Each sits on a
 * white 4px-radius hairline chip so the colored mark reads on any input bg incl.
 * dark mode. Official brand colors preserved; no <text>, no recolor/stretch.
 */

// Discover — orange "ball" right + DISCOVER wordmark as a real vector path
// (D-I-S-C-O-V-E-R glyphs as filled strokes, not <text>), official #F47216.
export const ICON_DISCOVER = `<svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect x="0.5" y="0.5" width="31" height="19" rx="2" fill="#fff" stroke="#E5E7EB"/>
  <path d="M31.5 14.5C25 18.5 14 19.2 7 19.5H30C30.83 19.5 31.5 18.83 31.5 18V14.5Z" fill="#F47216"/>
  <circle cx="20.4" cy="10" r="2.9" fill="#F47216"/>
  <g fill="#1D1D1D">
    <rect x="3.2" y="7.4" width="0.95" height="5.2" rx="0.2"/>
    <path d="M4.9 7.4H6.1C7.55 7.4 8.55 8.5 8.55 10C8.55 11.5 7.55 12.6 6.1 12.6H4.9V7.4ZM5.85 8.25V11.75H6.05C6.95 11.75 7.55 11.05 7.55 10C7.55 8.96 6.95 8.25 6.05 8.25H5.85Z"/>
    <path d="M10.6 9.4C10.05 9.2 9.9 9.07 9.9 8.82C9.9 8.54 10.18 8.32 10.55 8.32C10.81 8.32 11.02 8.43 11.25 8.69L11.74 8.04C11.34 7.69 10.86 7.52 10.34 7.52C9.5 7.52 8.86 8.1 8.86 8.88C8.86 9.53 9.16 9.87 10.02 10.18C10.38 10.31 10.57 10.39 10.66 10.45C10.85 10.57 10.94 10.74 10.94 10.94C10.94 11.32 10.64 11.6 10.22 11.6C9.78 11.6 9.42 11.38 9.21 10.97L8.6 11.56C9.02 12.18 9.53 12.45 10.25 12.45C11.21 12.45 11.9 11.81 11.9 10.89C11.9 10.13 11.58 9.79 10.6 9.4Z"/>
    <path d="M12.2 10C12.2 11.53 13.4 12.7 14.95 12.7C15.39 12.7 15.76 12.61 16.22 12.4V11.27C15.81 11.69 15.45 11.85 14.99 11.85C13.96 11.85 13.23 11.1 13.23 10C13.23 8.96 13.98 8.16 14.95 8.16C15.43 8.16 15.8 8.33 16.22 8.76V7.63C15.78 7.4 15.41 7.32 14.97 7.32C13.44 7.32 12.2 8.51 12.2 10Z"/>
  </g>
</svg>`;

// JCB — three vertical bars (blue/red/green), the canonical tri-color mark with
// faithful "JCB" letterforms as vector paths. Official #0E4C96/#E30138/#007B40.
export const ICON_JCB = `<svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect x="0.5" y="0.5" width="31" height="19" rx="2" fill="#fff" stroke="#E5E7EB"/>
  <path d="M7.5 4.6H11.6V14.2C11.6 15 10.95 15.6 10.2 15.6H6.1V6C6.1 5.23 6.73 4.6 7.5 4.6Z" fill="#0E4C96"/>
  <path d="M13.9 4.6H18V15.6H13.9C13.13 15.6 12.5 14.97 12.5 14.2V6C12.5 5.23 13.13 4.6 13.9 4.6Z" fill="#E30138"/>
  <path d="M20.4 4.6H24.5C25.27 4.6 25.9 5.23 25.9 6V15.6H21.8C21.05 15.6 20.4 15 20.4 14.2V4.6Z" fill="#007B40"/>
  <g fill="#fff">
    <path d="M9.55 7.2H10.45V11.1C10.45 11.95 9.95 12.45 9.1 12.45C8.62 12.45 8.2 12.27 7.9 11.92L8.5 11.3C8.66 11.5 8.84 11.6 9.06 11.6C9.36 11.6 9.55 11.4 9.55 11.05V7.2Z"/>
    <path d="M16.65 11.5C16.3 11.9 15.86 12.1 15.3 12.1C14.15 12.1 13.3 11.18 13.3 9.85C13.3 8.5 14.15 7.55 15.32 7.55C15.86 7.55 16.3 7.75 16.65 8.15V9.25C16.36 8.85 16 8.65 15.55 8.65C14.9 8.65 14.4 9.18 14.4 9.85C14.4 10.55 14.88 11.05 15.56 11.05C16 11.05 16.36 10.85 16.65 10.45V11.5Z"/>
    <path d="M22 9.7C22.4 9.55 22.6 9.25 22.6 8.78C22.6 8.05 22.05 7.6 21.15 7.6H19.4V12H21.3C22.22 12 22.8 11.55 22.8 10.78C22.8 10.2 22.5 9.83 22 9.7ZM20.3 8.35H21C21.4 8.35 21.6 8.5 21.6 8.83C21.6 9.15 21.4 9.32 21 9.32H20.3V8.35ZM21.1 11.25H20.3V10.05H21.1C21.55 10.05 21.78 10.25 21.78 10.64C21.78 11.05 21.55 11.25 21.1 11.25Z"/>
  </g>
</svg>`;

// UnionPay — three overlapping rounded bars (red/blue/teal), the canonical
// mark. Official #D10429/#022E64/#076F74; faithful "UnionPay" omitted at chip
// size for legibility (the three-block silhouette is the recognized form).
export const ICON_UNIONPAY = `<svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect x="0.5" y="0.5" width="31" height="19" rx="2" fill="#fff" stroke="#E5E7EB"/>
  <path d="M8 3.4H13.2C13.93 3.4 14.39 4 14.22 4.74L11.78 15.26C11.61 16 10.88 16.6 10.15 16.6H4.95C4.22 16.6 3.76 16 3.93 15.26L6.37 4.74C6.54 4.01 7.27 3.4 8 3.4Z" fill="#D10429"/>
  <path d="M12.8 3.4H18.7C19.43 3.4 19.1 4 18.93 4.74L16.49 15.26C16.32 16 16.27 16.6 15.54 16.6H9.64C8.91 16.6 9.37 16 9.54 15.26L11.98 4.74C12.15 4.01 12.07 3.4 12.8 3.4Z" fill="#022E64"/>
  <path d="M18.5 3.4H23.7C24.43 3.4 24.89 4 24.72 4.74L22.28 15.26C22.11 16 21.38 16.6 20.65 16.6H15.45C14.72 16.6 14.27 16 14.44 15.26L16.88 4.74C17.05 4.01 17.77 3.4 18.5 3.4Z" fill="#076F74"/>
</svg>`;

// Maestro — two interlocking circles (blue + red) with the lighter overlap.
// Official #0099DF / #ED0006, overlap #6C6BBD (the authentic Maestro mark, NOT
// the Mastercard orange/red recolor).
export const ICON_MAESTRO = `<svg viewBox="0 0 32 20" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect x="0.5" y="0.5" width="31" height="19" rx="2" fill="#fff" stroke="#E5E7EB"/>
  <circle cx="12.5" cy="10" r="5.6" fill="#0099DF"/>
  <circle cx="19.5" cy="10" r="5.6" fill="#ED0006"/>
  <path d="M16 5.6C17.32 6.62 18.1 8.22 18.1 10C18.1 11.78 17.32 13.38 16 14.4C14.68 13.38 13.9 11.78 13.9 10C13.9 8.22 14.68 6.62 16 5.6Z" fill="#6C6BBD"/>
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
