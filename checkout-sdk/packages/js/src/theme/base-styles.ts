/**
 * Base CSS styles for all NexusPay elements.
 * Uses CSS custom properties from tokens.ts via css-properties.ts.
 * These styles are injected into the iframe and host page.
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
  padding: calc(var(--nxp-spacing-unit, 4px) * 3);
  font-family: var(--nxp-font-family);
  font-size: 1rem;
  font-weight: var(--nxp-font-weight-normal, 400);
  color: var(--nxp-color-text, #1A1A2E);
  background-color: var(--nxp-color-background, #FFFFFF);
  border: 1px solid var(--nxp-border-default, #E5E7EB);
  border-radius: var(--nxp-border-radius, 8px);
  outline: none;
  transition: border-color 150ms ease, box-shadow 150ms ease;
  -webkit-appearance: none;
  appearance: none;
}

.Input::placeholder {
  color: #9CA3AF;
}

.Input:hover {
  border-color: var(--nxp-border-hover, #D1D5DB);
}

.Input:focus {
  border-color: var(--nxp-color-primary, #0066FF);
  box-shadow: 0 0 0 3px rgba(var(--nxp-color-primary, #0066FF), var(--nxp-focus-ring-alpha, 0.15));
}

.Input--error {
  border-color: var(--nxp-color-danger, #DC2626);
}

.Input--error:focus {
  box-shadow: 0 0 0 3px rgba(var(--nxp-color-danger, #DC2626), var(--nxp-focus-ring-alpha, 0.15));
}

.Input:disabled {
  opacity: var(--nxp-disabled-opacity, 0.5);
  cursor: not-allowed;
}

/* Autofill override */
.Input:-webkit-autofill {
  -webkit-box-shadow: 0 0 0 1000px var(--nxp-color-background, #FFFFFF) inset;
  -webkit-text-fill-color: var(--nxp-color-text, #1A1A2E);
}

/* --- Label --- */

.Label {
  display: block;
  margin-bottom: calc(var(--nxp-spacing-unit, 4px) * 1.5);
  font-family: var(--nxp-font-family);
  font-size: 0.875rem;
  font-weight: var(--nxp-font-weight-bold, 600);
  color: var(--nxp-color-text, #1A1A2E);
}

/* --- Error --- */

.Error {
  display: block;
  margin-top: calc(var(--nxp-spacing-unit, 4px) * 1.5);
  font-family: var(--nxp-font-family);
  font-size: 0.8125rem;
  color: var(--nxp-color-danger, #DC2626);
  animation: nxp-slide-in var(--nxp-anim-error-slide-in, 200ms) ease-out;
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

/* --- Tab --- */

.Tab {
  display: inline-flex;
  align-items: center;
  gap: calc(var(--nxp-spacing-unit, 4px) * 2);
  padding: calc(var(--nxp-spacing-unit, 4px) * 3) calc(var(--nxp-spacing-unit, 4px) * 4);
  font-family: var(--nxp-font-family);
  font-size: 0.875rem;
  font-weight: var(--nxp-font-weight-normal, 400);
  color: var(--nxp-color-text, #1A1A2E);
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: all var(--nxp-anim-tab-switch, 200ms) ease-in-out;
  white-space: nowrap;
}

.Tab:hover {
  background-color: rgba(var(--nxp-color-primary, #0066FF), 0.04);
}

.Tab--selected {
  font-weight: var(--nxp-font-weight-bold, 600);
  border-bottom-color: var(--nxp-color-primary, #0066FF);
  background-color: rgba(var(--nxp-color-primary, #0066FF), 0.06);
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
  gap: calc(var(--nxp-spacing-unit, 4px) * 2);
  width: 100%;
  padding: calc(var(--nxp-spacing-unit, 4px) * 3) calc(var(--nxp-spacing-unit, 4px) * 6);
  font-family: var(--nxp-font-family);
  font-size: 1rem;
  font-weight: var(--nxp-font-weight-bold, 600);
  color: #FFFFFF;
  background-color: var(--nxp-color-primary, #0066FF);
  border: none;
  border-radius: var(--nxp-border-radius, 8px);
  cursor: pointer;
  transition: background-color 150ms ease, opacity var(--nxp-anim-submit-loading, 300ms) ease;
  min-height: 44px;
}

.Button:hover {
  filter: brightness(0.9);
}

.Button:disabled {
  opacity: var(--nxp-disabled-opacity, 0.5);
  cursor: not-allowed;
}

.Button--loading {
  pointer-events: none;
}

.Button__spinner {
  width: 20px;
  height: 20px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #FFFFFF;
  border-radius: 50%;
  animation: nxp-spin 600ms linear infinite;
}

@keyframes nxp-spin {
  to { transform: rotate(360deg); }
}

/* --- Brand Icon Crossfade --- */

.BrandIcon {
  position: absolute;
  right: calc(var(--nxp-spacing-unit, 4px) * 3);
  top: 50%;
  transform: translateY(-50%);
  width: 32px;
  height: 20px;
  transition: opacity var(--nxp-anim-brand-crossfade, 150ms) ease-out;
}

.BrandIcon--hidden {
  opacity: 0;
}

/* --- Skeleton Shimmer --- */

.Skeleton {
  background: linear-gradient(90deg,
    var(--nxp-border-default, #E5E7EB) 25%,
    var(--nxp-color-background, #FFFFFF) 50%,
    var(--nxp-border-default, #E5E7EB) 75%
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
  animation: nxp-stroke var(--nxp-anim-success-checkmark, 600ms) linear forwards;
}

.SuccessCheckmark__check {
  stroke: var(--nxp-color-success, #16A34A);
  stroke-width: 2;
  fill: none;
  stroke-dasharray: 48;
  stroke-dashoffset: 48;
  animation: nxp-stroke var(--nxp-anim-success-checkmark, 600ms) linear 300ms forwards;
}

@keyframes nxp-stroke {
  to { stroke-dashoffset: 0; }
}

/* --- Responsive: Mobile radio list --- */

@media (max-width: 639px) {
  .TabList {
    flex-direction: column;
    gap: calc(var(--nxp-spacing-unit, 4px) * 2);
  }

  .Tab {
    min-height: 44px;
    border-bottom: none;
    border: 1px solid var(--nxp-border-default, #E5E7EB);
    border-radius: var(--nxp-border-radius, 8px);
    width: 100%;
  }

  .Tab--selected {
    border-color: var(--nxp-color-primary, #0066FF);
    background-color: rgba(var(--nxp-color-primary, #0066FF), 0.06);
  }
}
`;
