// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import { NexusPayProvider, useNexusPayContext } from '../NexusPayProvider';

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
    constructor(key: string) {
      this.publishableKey = key;
    }
    loadSession = vi.fn().mockResolvedValue(mockSession);
    on = vi.fn();
    off = vi.fn();
    emit = vi.fn();
  }

  return {
    NexusPay: MockNexusPay,
  };
});

function TestConsumer() {
  const { nexuspay, session, error, loading } = useNexusPayContext();

  if (loading) return <div data-testid="loading">Loading...</div>;
  if (error) return <div data-testid="error">{error.message}</div>;
  if (session) return <div data-testid="session">{session.id}</div>;
  return <div data-testid="empty">No session</div>;
}

describe('NexusPayProvider', () => {
  afterEach(() => cleanup());
  it('renders children', () => {
    render(
      <NexusPayProvider publishableKey="pk_test" clientSecret="cs_test">
        <div data-testid="child">Hello</div>
      </NexusPayProvider>,
    );
    expect(screen.getByTestId('child')).toBeDefined();
  });

  it('provides loading state initially', () => {
    render(
      <NexusPayProvider publishableKey="pk_test" clientSecret="cs_test">
        <TestConsumer />
      </NexusPayProvider>,
    );
    expect(screen.getByTestId('loading')).toBeDefined();
  });

  it('provides session after loading', async () => {
    render(
      <NexusPayProvider publishableKey="pk_test" clientSecret="cs_test">
        <TestConsumer />
      </NexusPayProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId('session')).toBeDefined();
    });

    expect(screen.getByTestId('session').textContent).toBe('ps_test_123');
  });
});
