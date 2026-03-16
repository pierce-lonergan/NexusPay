/**
 * PaymentSummary — amount and currency formatting.
 */

interface PaymentSummaryProps {
  amount: number;
  currency: string;
}

export function PaymentSummary({ amount, currency }: PaymentSummaryProps) {
  const formatted = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: currency.toUpperCase(),
  }).format(amount / 100);

  return (
    <div className="payment-summary">
      <h3 className="payment-summary__title">Order summary</h3>
      <div className="payment-summary__divider" />
      <div className="payment-summary__row payment-summary__row--total">
        <span>Total</span>
        <span className="payment-summary__amount">{formatted}</span>
      </div>
    </div>
  );
}
