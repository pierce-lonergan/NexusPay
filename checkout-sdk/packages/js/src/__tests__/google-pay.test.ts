// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { GooglePayHandler } from '../apm/google-pay';

describe('GooglePayHandler', () => {
  const config = {
    merchantId: 'BCR2DN123456',
    merchantName: 'Test Store',
    gatewayId: 'gw_test_123',
    countryCode: 'US',
    currencyCode: 'USD',
    amount: 2999,
    environment: 'TEST' as const,
  };

  let mockPaymentsClient: any;

  beforeEach(() => {
    mockPaymentsClient = {
      isReadyToPay: vi.fn().mockResolvedValue({ result: true }),
      createButton: vi.fn((opts: any) => {
        const btn = document.createElement('button');
        btn.textContent = 'Google Pay';
        btn.addEventListener('click', opts.onClick);
        return btn;
      }),
      loadPaymentData: vi.fn(),
    };

    (window as any).google = {
      payments: {
        api: {
          PaymentsClient: vi.fn(() => mockPaymentsClient),
        },
      },
    };
  });

  afterEach(() => {
    delete (window as any).google;
  });

  it('detects Google Pay unavailable when isReadyToPay returns false', async () => {
    mockPaymentsClient.isReadyToPay.mockResolvedValue({ result: false });
    const handler = new GooglePayHandler(config);
    const available = await handler.checkAvailability();
    expect(available).toBe(false);
    expect(handler.isAvailable()).toBe(false);
  });

  it('detects Google Pay available when API present', async () => {
    const readyHandler = vi.fn();
    const handler = new GooglePayHandler(config);
    handler.on('ready', readyHandler);

    const available = await handler.checkAvailability();
    expect(available).toBe(true);
    expect(handler.isAvailable()).toBe(true);
    expect(readyHandler).toHaveBeenCalled();
  });

  it('returns false when isReadyToPay returns false', async () => {
    mockPaymentsClient.isReadyToPay.mockResolvedValue({ result: false });

    const handler = new GooglePayHandler(config);
    const available = await handler.checkAvailability();
    expect(available).toBe(false);
  });

  it('creates a Google Pay button', async () => {
    const handler = new GooglePayHandler(config);
    await handler.checkAvailability();

    const button = handler.createButton();
    expect(button).toBeInstanceOf(HTMLElement);
    expect(mockPaymentsClient.createButton).toHaveBeenCalledWith(
      expect.objectContaining({
        buttonType: 'pay',
        buttonColor: 'default',
        buttonSizeMode: 'fill',
      }),
    );
  });

  it('emits token on successful payment', async () => {
    mockPaymentsClient.loadPaymentData.mockResolvedValue({
      paymentMethodData: {
        tokenizationData: { token: 'encrypted_token_data' },
        info: { cardNetwork: 'VISA', cardDetails: '1234' },
      },
    });

    const tokenHandler = vi.fn();
    const handler = new GooglePayHandler(config);
    handler.on('token', tokenHandler);

    await handler.checkAvailability();
    await handler.startPayment();

    expect(tokenHandler).toHaveBeenCalledWith({
      type: 'google_pay',
      tokenData: 'encrypted_token_data',
      cardNetwork: 'VISA',
      cardDetails: '1234',
    });
  });

  it('emits cancel when user cancels', async () => {
    mockPaymentsClient.loadPaymentData.mockRejectedValue({
      statusCode: 'CANCELED',
    });

    const cancelHandler = vi.fn();
    const handler = new GooglePayHandler(config);
    handler.on('cancel', cancelHandler);

    await handler.checkAvailability();
    await handler.startPayment();

    expect(cancelHandler).toHaveBeenCalled();
  });

  it('emits error when payment fails', async () => {
    mockPaymentsClient.loadPaymentData.mockRejectedValue(
      new Error('Payment failed'),
    );

    const errorHandler = vi.fn();
    const handler = new GooglePayHandler(config);
    handler.on('error', errorHandler);

    await handler.checkAvailability();
    await handler.startPayment();

    expect(errorHandler).toHaveBeenCalledWith({
      message: 'Payment failed',
    });
  });

  it('emits error when starting payment without availability', async () => {
    delete (window as any).google;
    const errorHandler = vi.fn();
    const handler = new GooglePayHandler(config);
    handler.on('error', errorHandler);

    await handler.startPayment();
    expect(errorHandler).toHaveBeenCalledWith({
      message: 'Google Pay is not available',
    });
  });
});
