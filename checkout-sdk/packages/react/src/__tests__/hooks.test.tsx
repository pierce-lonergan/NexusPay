// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import { NexusPayProvider } from '../NexusPayProvider';
import { useNexusPay } from '../useNexusPay';
import { useConfirmPayment } from '../useConfirmPayment';

// Mock @nexuspay/js
vi.mock('@nexuspay/js', () => {
  const mockSession = {
    id: 'ps_test_123',
    status: 'open',
    amount: 2999,
    currency: 'usd',
    allowedPaymentMethods: ['card'],
  };

  class MockNexusPay {
    publishableKey: string;
    constructor(key: string) {
      this.publishableKey = key;
    }
    loadSession = vi.fn().mockResolvedValue(mockSession);
    confirm = vi.fn().mockResolvedValue({ status: 'succeeded', paymentIntentId: 'pi_123' });
    on = vi.fn();
    off = vi.fn();
    emit = vi.fn();
  }

  return {
    NexusPay: MockNexusPay,
    ChallengeHandler: vi.fn(),
  };
});

function UseNexusPayConsumer() {
  const { nexuspay, session, loading } = useNexusPay();

  if (loading) return <div data-testid="loading">Loading</div>;
  return (
    <div data-testid="result">
      {nexuspay ? 'has-nexuspay' : 'no-nexuspay'}:{session?.id ?? 'no-session'}
    </div>
  );
}

function UseConfirmPaymentConsumer() {
  const { confirming, result, error, confirmPayment } = useConfirmPayment();

  return (
    <div>
      <div data-testid="state">
        {confirming ? 'confirming' : result ? result.status : error ? error.message : 'idle'}
      </div>
      <button data-testid="confirm-btn" onClick={() => confirmPayment('ptok_test')}>
        Confirm
      </button>
    </div>
  );
}

afterEach(() => cleanup());

describe('useNexusPay', () => {
  it('returns nexuspay and session from context', async () => {
    render(
      <NexusPayProvider publishableKey="pk_test" clientSecret="cs_test">
        <UseNexusPayConsumer />
      </NexusPayProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId('result')).toBeDefined();
    });

    expect(screen.getByTestId('result').textContent).toBe('has-nexuspay:ps_test_123');
  });
});

describe('useConfirmPayment', () => {
  it('starts in idle state', async () => {
    render(
      <NexusPayProvider publishableKey="pk_test" clientSecret="cs_test">
        <UseConfirmPaymentConsumer />
      </NexusPayProvider>,
    );

    // Wait for loading to complete
    await waitFor(() => {
      expect(screen.getByTestId('state').textContent).not.toBe('');
    });

    expect(screen.getByTestId('state').textContent).toBe('idle');
  });

  it('confirms payment and shows result', async () => {
    render(
      <NexusPayProvider publishableKey="pk_test" clientSecret="cs_test">
        <UseConfirmPaymentConsumer />
      </NexusPayProvider>,
    );

    // Wait for loading to complete
    await waitFor(() => {
      expect(screen.getByTestId('state').textContent).toBe('idle');
    });

    // Click confirm
    screen.getByTestId('confirm-btn').click();

    await waitFor(() => {
      expect(screen.getByTestId('state').textContent).toBe('succeeded');
    });
  });
});
