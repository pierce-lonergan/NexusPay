/**
 * Card validation utilities — Luhn check, BIN detection, expiry validation, PAN formatting.
 * Supports 8+ card networks per DESIGN.md specification.
 */

import type { CardBrand } from '../types';

/**
 * BIN ranges for card brand detection.
 * Order matters: more specific ranges first (Amex before Maestro).
 */
const BIN_RANGES: Array<{ brand: CardBrand; patterns: Array<[number, number] | number>; lengths: number[] }> = [
  {
    brand: 'amex',
    patterns: [34, 37],
    lengths: [15],
  },
  {
    brand: 'visa',
    patterns: [4],
    lengths: [13, 16, 19],
  },
  {
    brand: 'mastercard',
    patterns: [[51, 55], [2221, 2720]],
    lengths: [16],
  },
  {
    brand: 'discover',
    patterns: [6011, [644, 649], 65],
    lengths: [16, 19],
  },
  {
    brand: 'jcb',
    patterns: [[3528, 3589]],
    lengths: [16, 17, 18, 19],
  },
  {
    brand: 'diners',
    patterns: [[300, 305], 36, 38],
    lengths: [14, 16, 19],
  },
  {
    brand: 'unionpay',
    patterns: [62],
    lengths: [16, 17, 18, 19],
  },
  {
    brand: 'maestro',
    patterns: [5018, 5020, 5038, 6304, 6759, [6761, 6763]],
    lengths: [12, 13, 14, 15, 16, 17, 18, 19],
  },
];

/**
 * Detects card brand from a PAN (partial or full).
 */
export function detectBrand(pan: string): CardBrand {
  const digits = pan.replace(/\D/g, '');
  if (digits.length < 1) return 'unknown';

  for (const { brand, patterns } of BIN_RANGES) {
    for (const pattern of patterns) {
      if (typeof pattern === 'number') {
        const prefix = String(pattern);
        if (digits.startsWith(prefix)) return brand;
      } else {
        const [low, high] = pattern;
        const prefixLen = String(low).length;
        const cardPrefix = parseInt(digits.substring(0, prefixLen), 10);
        if (!isNaN(cardPrefix) && cardPrefix >= low && cardPrefix <= high) return brand;
      }
    }
  }

  return 'unknown';
}

/**
 * Validates a PAN using the Luhn algorithm.
 */
export function luhnCheck(pan: string): boolean {
  const digits = pan.replace(/\D/g, '');
  if (digits.length < 8) return false;

  let sum = 0;
  let alternate = false;

  for (let i = digits.length - 1; i >= 0; i--) {
    let n = parseInt(digits[i], 10);
    if (alternate) {
      n *= 2;
      if (n > 9) n -= 9;
    }
    sum += n;
    alternate = !alternate;
  }

  return sum % 10 === 0;
}

/**
 * Validates card expiry (MM/YY).
 */
export function isExpiryValid(month: number, year: number): boolean {
  if (month < 1 || month > 12) return false;

  const now = new Date();
  const currentYear = now.getFullYear() % 100;
  const currentMonth = now.getMonth() + 1;

  // Assume 2-digit year is 2000s
  const fullYear = year < 100 ? year : year % 100;

  if (fullYear < currentYear) return false;
  if (fullYear === currentYear && month < currentMonth) return false;

  return true;
}

/**
 * Formats a PAN with spaces based on card brand.
 * Visa/MC/Discover: 4-4-4-4 (or 4-4-4-4-3 for 19-digit)
 * Amex: 4-6-5
 */
export function formatPAN(pan: string): string {
  const digits = pan.replace(/\D/g, '');
  const brand = detectBrand(digits);

  if (brand === 'amex') {
    // 4-6-5 grouping
    const parts: string[] = [];
    if (digits.length > 0) parts.push(digits.substring(0, 4));
    if (digits.length > 4) parts.push(digits.substring(4, 10));
    if (digits.length > 10) parts.push(digits.substring(10, 15));
    return parts.join(' ');
  }

  // Default 4-4-4-4(-3) grouping
  const parts: string[] = [];
  for (let i = 0; i < digits.length; i += 4) {
    parts.push(digits.substring(i, i + 4));
  }
  return parts.join(' ');
}

/**
 * Returns the expected CVC length for a card brand.
 */
export function cvcLength(brand: CardBrand): number {
  return brand === 'amex' ? 4 : 3;
}

/**
 * Returns the maximum PAN length for a card brand.
 */
export function maxPanLength(brand: CardBrand): number {
  for (const entry of BIN_RANGES) {
    if (entry.brand === brand) {
      return Math.max(...entry.lengths);
    }
  }
  return 19;
}
