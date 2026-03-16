/**
 * FailureMessage — clear error message + "Try again" button.
 */

interface FailureMessageProps {
  error: string;
  onRetry: () => void;
}

export function FailureMessage({ error, onRetry }: FailureMessageProps) {
  return (
    <div className="failure">
      <div className="failure__icon">
        <svg viewBox="0 0 64 64" width="80" height="80">
          <circle cx="32" cy="32" r="28" stroke="#DC2626" strokeWidth="2" fill="none" />
          <path d="M24 24L40 40M40 24L24 40" stroke="#DC2626" strokeWidth="3" strokeLinecap="round" />
        </svg>
      </div>

      <h1 className="failure__title">Payment failed</h1>
      <p className="failure__message">{error}</p>

      <button
        type="button"
        className="failure__retry"
        onClick={onRetry}
      >
        Try again
      </button>
    </div>
  );
}
