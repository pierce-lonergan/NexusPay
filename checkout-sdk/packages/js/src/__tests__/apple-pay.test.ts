// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ApplePayHandler } from '../apm/apple-pay';

describe('ApplePayHandler', () => {
  const config = {
    merchantId: 'merchant.com.test',
    merchantName: 'Test Store',
    countryCode: 'US',
    currencyCode: 'USD',
    amount: 2999,
  };

  afterEach(() => {
    delete (window as any).ApplePaySession;
  });

  it('detects Apple Pay unavailable when API not present', async () => {
    const handler = new ApplePayHandler(config);
    const available = await handler.checkAvailability();
    expect(available).toBe(false);
    expect(handler.isAvailable()).toBe(false);
  });

  it('detects Apple Pay available when API present', async () => {
    (window as any).ApplePaySession = {
      canMakePayments: () => true,
    };

    const readyHandler = vi.fn();
    const handler = new ApplePayHandler(config);
    handler.on('ready', readyHandler);

    const available = await handler.checkAvailability();
    expect(available).toBe(true);
    expect(handler.isAvailable()).toBe(true);
    expect(readyHandler).toHaveBeenCalled();
  });

  it('creates a button element', () => {
    (window as any).ApplePaySession = {
      canMakePayments: () => true,
    };

    const handler = new ApplePayHandler(config);
    const button = handler.createButton({ style: 'black' });

    expect(button).toBeInstanceOf(HTMLElement);
    expect(button.getAttribute('role')).toBe('button');
    expect(button.getAttribute('aria-label')).toBe('Pay with Apple Pay');
  });

  it('emits error when starting payment without availability', async () => {
    const handler = new ApplePayHandler(config);
    const errorHandler = vi.fn();
    handler.on('error', errorHandler);

    await handler.startPayment();
    expect(errorHandler).toHaveBeenCalledWith({
      message: 'Apple Pay is not available',
    });
  });

  it('starts Apple Pay session when available', async () => {
    const mockSession = {
      begin: vi.fn(),
      onvalidatemerchant: null as any,
      onpaymentauthorized: null as any,
      oncancel: null as any,
    };

    const MockApplePaySession = vi.fn(() => mockSession);
    MockApplePaySession.canMakePayments = () => true;
    MockApplePaySession.STATUS_SUCCESS = 0;
    MockApplePaySession.STATUS_FAILURE = 1;
    (window as any).ApplePaySession = MockApplePaySession;

    const handler = new ApplePayHandler(config);
    await handler.checkAvailability();
    await handler.startPayment();

    expect(MockApplePaySession).toHaveBeenCalledWith(6, expect.any(Object));
    expect(mockSession.begin).toHaveBeenCalled();
  });

  it('emits cancel when session is cancelled', async () => {
    const mockSession = {
      begin: vi.fn(),
      onvalidatemerchant: null as any,
      onpaymentauthorized: null as any,
      oncancel: null as any,
    };

    const MockApplePaySession = vi.fn(() => mockSession);
    MockApplePaySession.canMakePayments = () => true;
    (window as any).ApplePaySession = MockApplePaySession;

    const cancelHandler = vi.fn();
    const handler = new ApplePayHandler(config);
    handler.on('cancel', cancelHandler);

    await handler.checkAvailability();
    await handler.startPayment();

    // Trigger cancel
    mockSession.oncancel();
    expect(cancelHandler).toHaveBeenCalled();
  });
});
