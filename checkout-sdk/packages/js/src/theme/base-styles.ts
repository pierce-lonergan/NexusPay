/**
 * Base CSS styles for all NexusPay elements.
 * Uses CSS custom properties from tokens.ts via css-properties.ts.
 * These styles are injected into the iframe and host page.
 *
 * P1: the focus ring + tab tints use color-mix(in srgb, ...) — NEVER the
 * invalid rgba(var(--hex), alpha) form, which the CSS parser drops (renders
 * nothing). This mirrors the iframe side (card-frame.css) so the seam is
 * invisible. All easing/duration are tokens, never literals.
 */

export const BASE_STYLES = /* css */ `
/* Reset */
*, *::before, *::after {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html {
  font-size: var(--nxp-font-size-base, 16px);
}

/* --- Input --- */

.Input {
  width: 100%;
  padding: var(--nxp-space-3, calc(var(--nxp-spacing-unit, 4px) * 3));
  font-family: var(--nxp-font-family);
  font-size: 1rem;
  font-weight: var(--nxp-font-weight-normal, 400);
  line-height: var(--nxp-font-line-height, 1.5);
  color: var(--nxp-color-text, #1A1A2E);
  background-color: var(--nxp-color-surface, var(--nxp-color-background, #FFFFFF));
  border: 1px solid var(--nxp-color-border, var(--nxp-border-default, #E5E7EB));
  border-radius: var(--nxp-border-radius, 8px);
  outline: none;
  transition:
    border-color var(--nxp-dur-fast, 150ms) var(--nxp-ease-standard, ease),
    box-shadow var(--nxp-dur-fast, 150ms) var(--nxp-ease-out, ease);
  -webkit-appearance: none;
  appearance: none;
}

.Input::placeholder {
  color: var(--nxp-color-text-placeholder, #9CA3AF);
}

.Input:hover {
  border-color: var(--nxp-color-border-hover, var(--nxp-border-hover, #D1D5DB));
}

/*
 * Two-layer focus ring (premium/cheap divider): a 1px solid inner ring
 * (guarantees >=3:1 for SC 1.4.11) plus a 3px translucent halo. Text inputs use
 * :focus (so the ring shows while typing); buttons/tabs use :focus-visible.
 */
.Input:focus {
  border-color: var(--nxp-color-primary, #0066FF);
  box-shadow:
    0 0 0 1px var(--nxp-color-primary, #0066FF),
    0 0 0 var(--nxp-focus-ring-width, 3px) color-mix(in srgb, var(--nxp-color-primary, #0066FF) 18%, transparent);
}

.Input--error {
  border-color: var(--nxp-color-danger, #DC2626);
}

.Input--error:focus {
  box-shadow:
    0 0 0 1px var(--nxp-color-danger, #DC2626),
    0 0 0 var(--nxp-focus-ring-width, 3px) color-mix(in srgb, var(--nxp-color-danger, #DC2626) 18%, transparent);
}

.Input:disabled {
  opacity: var(--nxp-disabled-opacity, 0.5);
  cursor: not-allowed;
}

/* Autofill override */
.Input:-webkit-autofill {
  -webkit-box-shadow: 0 0 0 1000px var(--nxp-color-surface, var(--nxp-color-background, #FFFFFF)) inset;
  -webkit-text-fill-color: var(--nxp-color-text, #1A1A2E);
}

/* --- Label --- */

.Label {
  display: block;
  margin-bottom: var(--nxp-space-2, calc(var(--nxp-spacing-unit, 4px) * 1.5));
  font-family: var(--nxp-font-family);
  font-size: var(--nxp-font-size-label, 0.875rem);
  font-weight: var(--nxp-font-weight-medium, 500);
  color: var(--nxp-color-text, #1A1A2E);
}

/* --- Error --- */

.Error {
  display: block;
  margin-top: var(--nxp-space-2, calc(var(--nxp-spacing-unit, 4px) * 1.5));
  font-family: var(--nxp-font-family);
  font-size: var(--nxp-font-size-sm, 0.8125rem);
  color: var(--nxp-color-danger, #DC2626);
  animation: nxp-slide-in var(--nxp-anim-error-slide-in, 200ms) var(--nxp-ease-out, ease-out);
}

@keyframes nxp-slide-in {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* One-shot horizontal shake for hard validation failures (never looped). */
@keyframes nxp-shake {
  0% { transform: translateX(0); }
  20% { transform: translateX(-6px); }
  40% { transform: translateX(6px); }
  60% { transform: translateX(-4px); }
  80% { transform: translateX(4px); }
  100% { transform: translateX(0); }
}

.Input--shake {
  animation: nxp-shake 320ms var(--nxp-ease-standard, ease-in-out);
}

/* --- Tab --- */

.Tab {
  display: inline-flex;
  align-items: center;
  gap: var(--nxp-space-2, calc(var(--nxp-spacing-unit, 4px) * 2));
  padding: var(--nxp-space-3, calc(var(--nxp-spacing-unit, 4px) * 3)) var(--nxp-space-4, calc(var(--nxp-spacing-unit, 4px) * 4));
  font-family: var(--nxp-font-family);
  font-size: var(--nxp-font-size-label, 0.875rem);
  font-weight: var(--nxp-font-weight-normal, 400);
  color: var(--nxp-color-text, #1A1A2E);
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  border-radius: var(--nxp-border-radius, 8px) var(--nxp-border-radius, 8px) 0 0;
  cursor: pointer;
  transition:
    color var(--nxp-anim-tab-switch, 200ms) var(--nxp-ease-standard, ease-in-out),
    background-color var(--nxp-anim-tab-switch, 200ms) var(--nxp-ease-standard, ease-in-out),
    border-color var(--nxp-anim-tab-switch, 200ms) var(--nxp-ease-standard, ease-in-out),
    box-shadow var(--nxp-anim-tab-switch, 200ms) var(--nxp-ease-standard, ease-in-out);
  white-space: nowrap;
}

.Tab:hover {
  background-color: color-mix(in srgb, var(--nxp-color-primary, #0066FF) 4%, transparent);
}

.Tab:focus-visible {
  outline: none;
  box-shadow:
    0 0 0 1px var(--nxp-color-primary, #0066FF),
    0 0 0 var(--nxp-focus-ring-width, 3px) color-mix(in srgb, var(--nxp-color-primary, #0066FF) 18%, transparent);
}

.Tab--selected {
  font-weight: var(--nxp-font-weight-bold, 600);
  border-bottom-color: var(--nxp-color-primary, #0066FF);
  background-color: color-mix(in srgb, var(--nxp-color-primary, #0066FF) 6%, transparent);
  /* Stripe-style selected-tab ring */
  box-shadow:
    0 1px 1px rgba(0, 0, 0, 0.03),
    0 3px 6px rgba(18, 42, 66, 0.02),
    0 0 0 2px var(--nxp-color-primary, #0066FF);
}

.Tab:disabled {
  opacity: var(--nxp-disabled-opacity, 0.5);
  cursor: not-allowed;
}

.Tab__icon {
  width: 24px;
  height: 16px;
  flex-shrink: 0;
}

/* --- Button --- */

.Button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--nxp-space-2, calc(var(--nxp-spacing-unit, 4px) * 2));
  width: 100%;
  padding: var(--nxp-space-3, calc(var(--nxp-spacing-unit, 4px) * 3)) var(--nxp-space-6, calc(var(--nxp-spacing-unit, 4px) * 6));
  font-family: var(--nxp-font-family);
  font-size: 1rem;
  font-weight: var(--nxp-font-weight-bold, 600);
  color: var(--nxp-on-primary, #FFFFFF);
  /* Flat brand fill + a very subtle same-hue vertical gradient (top ~4% lighter)
     for tactility — NOT a multi-hue gradient. */
  background-color: var(--nxp-color-primary, #0066FF);
  background-image: linear-gradient(
    to bottom,
    color-mix(in srgb, var(--nxp-color-primary, #0066FF) 96%, #FFFFFF),
    var(--nxp-color-primary, #0066FF)
  );
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.15), var(--nxp-shadow-sm, none);
  border: none;
  border-radius: var(--nxp-button-border-radius, var(--nxp-border-radius, 8px));
  cursor: pointer;
  transition:
    background-color var(--nxp-dur-fast, 150ms) var(--nxp-ease-standard, ease),
    box-shadow var(--nxp-dur-fast, 150ms) var(--nxp-ease-standard, ease),
    transform var(--nxp-dur-instant, 100ms) var(--nxp-ease-standard, ease),
    opacity var(--nxp-anim-submit-loading, 300ms) var(--nxp-ease-standard, ease);
  min-height: 48px;
}

.Button:hover:not(:disabled) {
  /* -8% lightness shift via color-mix toward black (replaces filter:brightness). */
  background-color: color-mix(in srgb, var(--nxp-color-primary, #0066FF) 92%, #000000);
  background-image: none;
  transform: translateY(-1px);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.15), var(--nxp-shadow-md, none);
}

.Button:active:not(:disabled) {
  transform: translateY(0) scale(0.98);
}

.Button:focus-visible {
  outline: none;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.15),
    0 0 0 1px var(--nxp-color-primary, #0066FF),
    0 0 0 var(--nxp-focus-ring-width, 3px) color-mix(in srgb, var(--nxp-color-primary, #0066FF) 25%, transparent);
}

.Button:disabled {
  opacity: var(--nxp-disabled-opacity, 0.5);
  cursor: not-allowed;
}

.Button--loading {
  pointer-events: none;
  cursor: wait;
}

.Button__spinner {
  width: 20px;
  height: 20px;
  border: 2px solid color-mix(in srgb, var(--nxp-on-primary, #FFFFFF) 30%, transparent);
  border-top-color: var(--nxp-on-primary, #FFFFFF);
  border-radius: 50%;
  animation: nxp-spin 600ms linear infinite;
}

@keyframes nxp-spin {
  to { transform: rotate(360deg); }
}

/* --- Brand Icon Crossfade --- */

.BrandIcon {
  position: absolute;
  right: var(--nxp-space-3, calc(var(--nxp-spacing-unit, 4px) * 3));
  top: 50%;
  transform: translateY(-50%);
  width: 32px;
  height: 20px;
  transition:
    opacity var(--nxp-anim-brand-crossfade, 150ms) var(--nxp-ease-out, ease-out),
    transform var(--nxp-anim-brand-crossfade, 150ms) var(--nxp-ease-out, ease-out);
}

.BrandIcon--hidden {
  opacity: 0;
  transform: translateY(-50%) scale(0.9);
}

/* --- Skeleton Shimmer --- */

.Skeleton {
  background: linear-gradient(90deg,
    var(--nxp-color-border, var(--nxp-border-default, #E5E7EB)) 25%,
    var(--nxp-color-surface, var(--nxp-color-background, #FFFFFF)) 50%,
    var(--nxp-color-border, var(--nxp-border-default, #E5E7EB)) 75%
  );
  background-size: 200% 100%;
  animation: nxp-shimmer var(--nxp-anim-skeleton-shimmer, 1500ms) infinite linear;
  border-radius: var(--nxp-border-radius, 8px);
}

@keyframes nxp-shimmer {
  from { background-position: 200% 0; }
  to { background-position: -200% 0; }
}

/* --- Success Checkmark --- */

.SuccessCheckmark {
  width: 64px;
  height: 64px;
}

.SuccessCheckmark__circle {
  stroke: var(--nxp-color-success, #16A34A);
  stroke-width: 2;
  fill: none;
  stroke-dasharray: 166;
  stroke-dashoffset: 166;
  animation: nxp-stroke var(--nxp-anim-success-checkmark, 600ms) var(--nxp-ease-spring, ease-out) 200ms forwards;
}

.SuccessCheckmark__check {
  stroke: var(--nxp-color-success, #16A34A);
  stroke-width: 2;
  fill: none;
  stroke-dasharray: 48;
  stroke-dashoffset: 48;
  animation: nxp-stroke 400ms var(--nxp-ease-spring, ease-out) 700ms forwards;
}

@keyframes nxp-stroke {
  to { stroke-dashoffset: 0; }
}

/* --- Responsive: Mobile radio list --- */

@media (max-width: 639px) {
  .TabList {
    flex-direction: column;
    gap: var(--nxp-space-2, calc(var(--nxp-spacing-unit, 4px) * 2));
  }

  .Tab {
    min-height: 44px;
    border-bottom: none;
    border: 1px solid var(--nxp-color-border, var(--nxp-border-default, #E5E7EB));
    border-radius: var(--nxp-border-radius, 8px);
    width: 100%;
  }

  .Tab--selected {
    border-color: var(--nxp-color-primary, #0066FF);
    background-color: color-mix(in srgb, var(--nxp-color-primary, #0066FF) 6%, transparent);
  }
}

/* --- Motion a11y: prefers-reduced-motion (mirrors card-frame.css) --- */

/*
 * Remove motion but NEVER the focus ring or error visibility. Visual state
 * (focus ring, error message, brand icon, success check) still changes — only
 * the transition/animation is removed.
 */
@media (prefers-reduced-motion: reduce) {
  .Input,
  .Tab,
  .Button,
  .BrandIcon {
    transition: none;
  }

  .Error,
  .Input--shake,
  .Button__spinner,
  .Skeleton,
  .SuccessCheckmark__circle,
  .SuccessCheckmark__check {
    animation: none;
  }

  /* Reduced motion shows the final checkmark statically. */
  .SuccessCheckmark__circle,
  .SuccessCheckmark__check {
    stroke-dashoffset: 0;
  }
}

/* --- Forced colors (Windows High Contrast) --- */

/*
 * box-shadow rings are invisible in forced-colors mode, so fall back to a real
 * outline using system colors. The transparent outline reserved here becomes
 * visible automatically.
 */
@media (forced-colors: active) {
  .Input,
  .Tab,
  .Button {
    border: 1px solid CanvasText;
  }

  .Input:focus,
  .Tab:focus-visible,
  .Button:focus-visible {
    outline: 2px solid Highlight;
    outline-offset: var(--nxp-focus-outline-offset, 2px);
  }

  .Input--error {
    border-color: LinkText;
  }
}
`;
