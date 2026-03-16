import { describe, it, expect } from 'vitest';
import { detectBrand, luhnCheck, isExpiryValid, formatPAN, cvcLength } from '../utils/card-validator';

describe('detectBrand', () => {
  it('detects Visa', () => {
    expect(detectBrand('4242424242424242')).toBe('visa');
    expect(detectBrand('4')).toBe('visa');
  });

  it('detects Mastercard (51-55 range)', () => {
    expect(detectBrand('5555555555554444')).toBe('mastercard');
    expect(detectBrand('5105105105105100')).toBe('mastercard');
  });

  it('detects Mastercard (2221-2720 range)', () => {
    expect(detectBrand('2223000048400011')).toBe('mastercard');
    expect(detectBrand('2720')).toBe('mastercard');
  });

  it('detects Amex', () => {
    expect(detectBrand('378282246310005')).toBe('amex');
    expect(detectBrand('371449635398431')).toBe('amex');
    expect(detectBrand('34')).toBe('amex');
  });

  it('detects Discover', () => {
    expect(detectBrand('6011111111111117')).toBe('discover');
    expect(detectBrand('6500000000000002')).toBe('discover');
  });

  it('detects JCB', () => {
    expect(detectBrand('3530111333300000')).toBe('jcb');
    expect(detectBrand('3589')).toBe('jcb');
  });

  it('detects Diners Club', () => {
    expect(detectBrand('30569309025904')).toBe('diners');
    expect(detectBrand('36')).toBe('diners');
  });

  it('detects UnionPay', () => {
    expect(detectBrand('6200000000000005')).toBe('unionpay');
    expect(detectBrand('62')).toBe('unionpay');
  });

  it('detects Maestro', () => {
    expect(detectBrand('5018')).toBe('maestro');
    expect(detectBrand('6759')).toBe('maestro');
    expect(detectBrand('6761')).toBe('maestro');
  });

  it('returns unknown for unrecognized BINs', () => {
    expect(detectBrand('9999999999999999')).toBe('unknown');
    expect(detectBrand('')).toBe('unknown');
  });
});

describe('luhnCheck', () => {
  it('validates correct Visa number', () => {
    expect(luhnCheck('4242424242424242')).toBe(true);
  });

  it('validates correct Amex number', () => {
    expect(luhnCheck('378282246310005')).toBe(true);
  });

  it('rejects invalid number', () => {
    expect(luhnCheck('4242424242424241')).toBe(false);
  });

  it('rejects too short input', () => {
    expect(luhnCheck('1234')).toBe(false);
  });

  it('ignores non-digit characters', () => {
    expect(luhnCheck('4242 4242 4242 4242')).toBe(true);
  });
});

describe('isExpiryValid', () => {
  it('accepts future dates', () => {
    const futureYear = (new Date().getFullYear() % 100) + 2;
    expect(isExpiryValid(12, futureYear)).toBe(true);
  });

  it('rejects past dates', () => {
    expect(isExpiryValid(1, 20)).toBe(false);
  });

  it('rejects invalid months', () => {
    expect(isExpiryValid(0, 30)).toBe(false);
    expect(isExpiryValid(13, 30)).toBe(false);
  });
});

describe('formatPAN', () => {
  it('formats Visa as 4-4-4-4', () => {
    expect(formatPAN('4242424242424242')).toBe('4242 4242 4242 4242');
  });

  it('formats Amex as 4-6-5', () => {
    expect(formatPAN('378282246310005')).toBe('3782 822463 10005');
  });

  it('handles partial input', () => {
    expect(formatPAN('424242')).toBe('4242 42');
  });
});

describe('cvcLength', () => {
  it('returns 4 for Amex', () => {
    expect(cvcLength('amex')).toBe(4);
  });

  it('returns 3 for other brands', () => {
    expect(cvcLength('visa')).toBe(3);
    expect(cvcLength('mastercard')).toBe(3);
  });
});
