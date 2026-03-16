/**
 * SuccessConfirmation — animated checkmark SVG + redirect countdown.
 * stroke-dasharray → stroke-dashoffset, 600ms linear animation.
 */

import { useState, useEffect } from 'react';

interface SuccessConfirmationProps {
  amount: number;
  currency: string;
  redirectUrl?: string;
  redirectDelay?: number;
}

export function SuccessConfirmation({
  amount,
  currency,
  redirectUrl,
  redirectDelay = 5,
}: SuccessConfirmationProps) {
  const [countdown, setCountdown] = useState(redirectDelay);

  const formatted = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: currency.toUpperCase(),
  }).format(amount / 100);

  useEffect(() => {
    if (!redirectUrl) return;

    const interval = setInterval(() => {
      setCountdown((prev) => {
        if (prev <= 1) {
          clearInterval(interval);
          window.location.href = redirectUrl;
          return 0;
        }
        return prev - 1;
      });
    }, 1000);

    return () => clearInterval(interval);
  }, [redirectUrl, redirectDelay]);

  return (
    <div className="success">
      <div className="success__icon">
        <svg viewBox="0 0 64 64" width="80" height="80" className="success__checkmark">
          <circle
            cx="32" cy="32" r="28"
            stroke="var(--nxp-color-success, #16A34A)"
            strokeWidth="2"
            fill="none"
            className="success__circle"
          />
          <path
            d="M20 32L28 40L44 24"
            stroke="var(--nxp-color-success, #16A34A)"
            strokeWidth="3"
            fill="none"
            strokeLinecap="round"
            strokeLinejoin="round"
            className="success__check"
          />
        </svg>
      </div>

      <h1 className="success__title">Payment successful</h1>
      <p className="success__amount">{formatted}</p>
      <p className="success__message">Thank you for your payment.</p>

      {redirectUrl && countdown > 0 && (
        <p className="success__redirect">
          Redirecting in {countdown} second{countdown !== 1 ? 's' : ''}...
        </p>
      )}
    </div>
  );
}
