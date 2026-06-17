/**
 * CheckoutPage — main checkout page.
 * Desktop (>=640px): two-column — floating form card left, sticky order summary right.
 * Mobile (<640px): single-column, collapsible summary, sticky pay bar, 44px min touch targets.
 */

import { useRef, useState } from 'react';
import { useNexusPay, useConfirmPayment, PaymentElement, AddressElement } from '@nexus-pay/react';
import type { PaymentMethodType, AddressData } from '@nexus-pay/js';
import { BrandingHeader } from './components/BrandingHeader';
import { PaymentSummary } from './components/PaymentSummary';
import { SuccessConfirmation } from './components/SuccessConfirmation';
import { FailureMessage } from './components/FailureMessage';
import { LoadingSkeleton } from './components/LoadingSkeleton';
import { AcceptedCards } from './components/AcceptedCards';

/**
 * Spinner gate (ms): sub-second confirmations skip the spinner so a brief flash
 * never reads as jank (spec state 4 — PROCESSING).
 */
const SPINNER_GATE_MS = 200;

export function CheckoutPage() {
  const { session, loading, error: sessionError } = useNexusPay();
  const { confirming, result, error: confirmError, confirmPayment, reset } = useConfirmPayment();

  const [paymentComplete, setPaymentComplete] = useState(false);
  const [, setSelectedMethod] = useState<PaymentMethodType>('card');
  const [, setAddressData] = useState<AddressData | null>(null);
  const [, setAddressComplete] = useState(false);
  const [showSpinner, setShowSpinner] = useState(false);
  // Double-submit guard: latches on first click, independent of async state.
  const submittingRef = useRef(false);
  const spinnerTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

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
  if (result?.status === 'succeeded') {
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
        onRetry={() => {
          submittingRef.current = false;
          reset();
        }}
      />
    );
  }

  const formattedAmount = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: session.currency.toUpperCase(),
  }).format(session.amount / 100);

  const handleSubmit = async () => {
    // Disable on first click to prevent double-submit.
    if (submittingRef.current) return;
    submittingRef.current = true;

    // Gate the spinner behind ~200ms so fast confirmations skip it.
    spinnerTimer.current = setTimeout(() => setShowSpinner(true), SPINNER_GATE_MS);

    try {
      // In a real implementation, tokenization would happen first.
      await confirmPayment('ptok_placeholder');
    } catch {
      // Error handled by useConfirmPayment.
    } finally {
      if (spinnerTimer.current) clearTimeout(spinnerTimer.current);
      setShowSpinner(false);
      submittingRef.current = false;
    }
  };

  const isProcessing = confirming;

  return (
    <div className="checkout">
      <BrandingHeader />

      <div className="checkout__layout">
        <div className="checkout__form">
          <div className="checkout__form-inner">
            <h2 className="checkout__title">Payment details</h2>

            {/* Accepted-card row (muted brand marks) above the card group. */}
            <AcceptedCards />

            {/* The ENCAPSULATED card-details group — the highest-ROI trust move.
                Distinct surface + 12px radius + soft card shadow + a lock header.
                Chrome lives on the PARENT around the PCI iframe. */}
            <section className="nxp-card-group" aria-label="Card details">
              <div className="nxp-card-group__header">
                <span className="nxp-card-group__title">
                  <svg
                    className="nxp-card-group__lock"
                    viewBox="0 0 16 16"
                    fill="none"
                    aria-hidden="true"
                  >
                    <rect x="3" y="7" width="10" height="7" rx="1.5" stroke="currentColor" strokeWidth="1.5" />
                    <path d="M5 7V5C5 3.34 6.34 2 8 2C9.66 2 11 3.34 11 5V7" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                  </svg>
                  Card details
                </span>
                <span className="nxp-card-group__secure">Encrypted &amp; secure</span>
              </div>

              <PaymentElement
                onChange={(data) => {
                  setSelectedMethod(data.paymentMethod);
                  setPaymentComplete(data.complete);
                }}
                className="checkout__payment-element"
              />
            </section>

            <h2 className="checkout__title">Billing address</h2>

            <AddressElement
              mode="billing"
              onChange={(data) => {
                setAddressData(data.value);
                setAddressComplete(data.complete);
              }}
              className="checkout__address-element"
            />

            <div className="checkout__paybar">
              <button
                type="button"
                className={`checkout__submit${isProcessing ? ' checkout__submit--loading' : ''}`}
                disabled={isProcessing || !paymentComplete}
                aria-busy={isProcessing}
                onClick={handleSubmit}
              >
                {/* In-place morph — the content box keeps a fixed footprint so the
                    button never collapses width while processing. */}
                <span className="checkout__submit-content" style={{ opacity: isProcessing && showSpinner ? 0 : 1 }}>
                  <svg className="checkout__lock-icon" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <rect x="3" y="7" width="10" height="7" rx="1.5" stroke="currentColor" strokeWidth="1.5" />
                    <path d="M5 7V5C5 3.34 6.34 2 8 2C9.66 2 11 3.34 11 5V7" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                  </svg>
                  Pay <span className="checkout__amount">{formattedAmount}</span>
                </span>
                {isProcessing && showSpinner && (
                  <span className="checkout__spinner" style={{ position: 'absolute' }} aria-hidden="true" />
                )}
              </button>

              <p className="checkout__secure-text">
                <svg viewBox="0 0 16 16" fill="none" width="12" height="12" aria-hidden="true">
                  <rect x="3" y="7" width="10" height="7" rx="1.5" stroke="currentColor" strokeWidth="1.5" />
                  <path d="M5 7V5C5 3.34 6.34 2 8 2C9.66 2 11 3.34 11 5V7" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                </svg>
                Your data is encrypted and secure
              </p>
              <p className="checkout__powered-by">Powered by NexusPay</p>
            </div>
          </div>
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
