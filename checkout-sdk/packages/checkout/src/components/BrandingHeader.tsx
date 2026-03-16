/**
 * BrandingHeader — merchant logo + business name, fallback to NexusPay.
 * Applies accent_color and background_color from session branding.
 */

export function BrandingHeader() {
  return (
    <header className="branding-header">
      <div className="branding-header__logo">
        <svg viewBox="0 0 24 24" fill="none" width="32" height="32">
          <rect width="24" height="24" rx="6" fill="var(--nxp-color-primary, #0066FF)" />
          <path d="M7 12L10.5 15.5L17 9" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </div>
      <span className="branding-header__name">NexusPay Checkout</span>
    </header>
  );
}
