// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest';
import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { NexusPayProvider } from '../NexusPayProvider';
import { PaymentElement } from '../PaymentElement';

// Mock @nexus-pay/js
vi.mock('@nexus-pay/js', () => {
  const mockSession = {
    id: 'ps_test_123',
    status: 'open',
    amount: 2999,
    currency: 'usd',
    allowedPaymentMethods: ['card'],
  };

  class MockNexusPay {
    publishableKey: string;
    httpClient = { baseUrl: 'https://api.test.com' };
    constructor(key: string) {
      this.publishableKey = key;
    }
    loadSession = vi.fn().mockResolvedValue(mockSession);
    on = vi.fn();
    off = vi.fn();
    emit = vi.fn();
  }

  class MockPaymentElement {
    mount = vi.fn();
    unmount = vi.fn();
    destroy = vi.fn();
    on = vi.fn((event: string, handler: Function) => {
      if (event === 'ready') {
        // Simulate ready after a tick
        setTimeout(() => handler(), 10);
      }
    });
    off = vi.fn();
  }

  return {
    NexusPay: MockNexusPay,
    PaymentElement: MockPaymentElement,
  };
});

describe('PaymentElement', () => {
  it('renders loading skeleton while loading', () => {
    const { container } = render(
      <NexusPayProvider publishableKey="pk_test" clientSecret="cs_test">
        <PaymentElement className="custom-class" />
      </NexusPayProvider>,
    );

    // Should have shimmer skeleton
    const skeleton = container.querySelector('[aria-hidden="true"]');
    expect(skeleton).not.toBeNull();
  });

  it('accepts className and style props', () => {
    const { container } = render(
      <NexusPayProvider publishableKey="pk_test" clientSecret="cs_test">
        <PaymentElement
          className="my-payment"
          style={{ marginTop: '20px' }}
        />
      </NexusPayProvider>,
    );

    const wrapper = container.querySelector('.my-payment');
    expect(wrapper).not.toBeNull();
    expect((wrapper as HTMLElement).style.marginTop).toBe('20px');
  });
});
