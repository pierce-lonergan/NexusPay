/**
 * CheckoutPage — main checkout page.
 * Desktop (>=640px): two-column — form left, order summary right.
 * Mobile (<640px): single-column, collapsible summary, 44px min touch targets.
 */

import { useState } from 'react';
import { useNexusPay, useConfirmPayment, PaymentElement, AddressElement } from '@nexuspay/react';
import type { PaymentMethodType, AddressData } from '@nexuspay/js';
import { BrandingHeader } from './components/BrandingHeader';
import { PaymentSummary } from './components/PaymentSummary';
import { SuccessConfirmation } from './components/SuccessConfirmation';
import { FailureMessage } from './components/FailureMessage';
import { LoadingSkeleton } from './components/LoadingSkeleton';

export function CheckoutPage() {
  const { session, loading, error: sessionError } = useNexusPay();
  const { confirming, result, error: confirmError, confirmPayment, reset } = useConfirmPayment();

  const [paymentComplete, setPaymentComplete] = useState(false);
  const [selectedMethod, setSelectedMethod] = useState<PaymentMethodType>('card');
  const [addressData, setAddressData] = useState<AddressData | null>(null);
  const [addressComplete, setAddressComplete] = useState(false);

  if (loading) return <LoadingSkeleton />;

  if (sessionError) {
    return (
      <div className="checkout-error">
        <h1>Session Error</h1>
        <p>{sessionError.message}</p>
      </div>
    );
  }

  if (!session) return <LoadingSkeleton />;

  // Success screen
  if (result?.status === 'succeeded' || paymentComplete) {
    return (
      <SuccessConfirmation
        amount={session.amount}
        currency={session.currency}
      />
    );
  }

  // Failure screen
  if (result?.status === 'failed' || confirmError) {
    return (
      <FailureMessage
        error={confirmError?.message ?? 'Payment failed'}
        onRetry={reset}
      />
    );
  }

  const formattedAmount = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: session.currency.toUpperCase(),
  }).format(session.amount / 100);

  return (
    <div className="checkout">
      <BrandingHeader />

      <div className="checkout__layout">
        <div className="checkout__form">
          <h2 className="checkout__title">Payment details</h2>

          <PaymentElement
            onChange={(data) => {
              setSelectedMethod(data.paymentMethod);
              setPaymentComplete(data.complete);
            }}
            className="checkout__payment-element"
          />

          <h2 className="checkout__title" style={{ marginTop: '24px' }}>
            Billing address
          </h2>

          <AddressElement
            mode="billing"
            onChange={(data) => {
              setAddressData(data.value);
              setAddressComplete(data.complete);
            }}
            className="checkout__address-element"
          />

          <button
            type="button"
            className="checkout__submit"
            disabled={confirming || !paymentComplete}
            onClick={async () => {
              try {
                // In a real implementation, tokenization would happen first
                await confirmPayment('ptok_placeholder');
              } catch {
                // Error handled by useConfirmPayment
              }
            }}
          >
            {confirming ? (
              <span className="checkout__spinner" />
            ) : (
              <>
                <svg className="checkout__lock-icon" viewBox="0 0 16 16" fill="none">
                  <rect x="3" y="7" width="10" height="7" rx="1.5" stroke="currentColor" strokeWidth="1.5"/>
                  <path d="M5 7V5C5 3.34 6.34 2 8 2C9.66 2 11 3.34 11 5V7" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
                </svg>
                Pay {formattedAmount}
              </>
            )}
          </button>

          <p className="checkout__secure-text">
            <svg viewBox="0 0 16 16" fill="none" width="12" height="12">
              <rect x="3" y="7" width="10" height="7" rx="1.5" stroke="currentColor" strokeWidth="1.5"/>
              <path d="M5 7V5C5 3.34 6.34 2 8 2C9.66 2 11 3.34 11 5V7" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
            </svg>
            Secured by NexusPay
          </p>
        </div>

        <div className="checkout__summary">
          <PaymentSummary
            amount={session.amount}
            currency={session.currency}
          />
        </div>
      </div>
    </div>
  );
}
