/**
 * Design tokens — the single source of truth for all visual constants.
 * Maps directly to the premium-checkout-spec.md specifications.
 */

import type { AppearanceVariables } from '../types';

/** Default theme variables (light theme) */
export const DEFAULT_VARIABLES: Required<AppearanceVariables> = {
  colorPrimary: '#0066FF',
  // The input/field surface the iframe consumes (kept white). The tinted page
  // background #F6F8FA lives at the SPA page level (checkout.css), so the
  // floating card reads against a tint without recoloring the card fields.
  colorBackground: '#FFFFFF',
  colorText: '#1A1A2E',
  colorDanger: '#DC2626',
  colorSuccess: '#16A34A',
  fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
  fontSizeBase: '16px',
  borderRadius: '8px',
  spacingUnit: '4px',
  fontWeightNormal: 400,
  fontWeightBold: 600,
  // --- P2: extended token set ---
  colorTextSecondary: '#6B7280',
  colorTextPlaceholder: '#9CA3AF',
  colorBorder: '#E5E7EB',
  colorBorderHover: '#D1D5DB',
  colorWarning: '#D97706',
  colorSurface: '#FFFFFF',
  onPrimary: '#FFFFFF',
  fontWeightMedium: 500,
  buttonBorderRadius: '8px',
};

/**
 * Night (dark) theme overrides — full parity with the light palette.
 * Elevation is expressed by a LIGHTER surface (one step up), not shadow, since
 * shadows barely read on dark. Danger/success shift lighter to hold 4.5:1.
 */
export const NIGHT_VARIABLES: Partial<AppearanceVariables> = {
  // Richer blue than the old #3B82F6: white-on-#2563EB is ~5.1:1 (passes AA 4.5:1
  // for the 16px/600 pay-button label), where #3B82F6 was only 3.68:1 and failed.
  // Still vivid against the #0F172A dark page.
  colorPrimary: '#2563EB',
  colorBackground: '#0F172A',
  colorSurface: '#1E293B',
  colorText: '#E2E8F0',
  colorTextSecondary: '#94A3B8',
  colorTextPlaceholder: '#64748B',
  colorBorder: '#334155',
  colorBorderHover: '#475569',
  colorDanger: '#F87171',
  colorSuccess: '#4ADE80',
  colorWarning: '#FBBF24',
  onPrimary: '#FFFFFF',
};

/** Flat theme overrides */
export const FLAT_VARIABLES: Partial<AppearanceVariables> = {
  borderRadius: '0',
  buttonBorderRadius: '0',
};

/**
 * Focus-ring alpha per scheme, emitted as --nxp-focus-ring-alpha.
 * NOTE: the two-layer ring's translucent halo opacity is set directly in the
 * CSS via color-mix (18% light / 25% dark), so this emitted token is a
 * merchant-readable knob, not the literal halo opacity. Kept at the historical
 * 0.15 default for backward compatibility.
 */
export const FOCUS_RING_ALPHA = {
  light: 0.15,
  dark: 0.25,
} as const;

/**
 * Easing tokens (cubic-beziers). Motion is themeable, not literal — this keeps
 * the two sides of the PCI iframe in lockstep.
 */
export const EASING_TOKENS = {
  /** Entrances: focus ring, label float, brand crossfade, error slide-in. */
  out: 'cubic-bezier(0.16, 1, 0.3, 1)',
  /** Hover / tab / border-color. */
  standard: 'cubic-bezier(0.4, 0, 0.2, 1)',
  /** Exits. */
  in: 'cubic-bezier(0.4, 0, 1, 1)',
  /** Success checkmark ONLY (the one place overshoot is allowed). */
  spring: 'cubic-bezier(0.34, 1.56, 0.64, 1)',
} as const;

/** Duration tokens (ms). */
export const DURATION_TOKENS = {
  instant: 100,
  fast: 150,
  base: 200,
  slow: 300,
} as const;

/** Animation durations (ms) */
export const ANIMATION = {
  brandCrossfade: 150,
  errorSlideIn: 200,
  tabSwitch: 200,
  submitLoading: 300,
  successCheckmark: 600,
  skeletonShimmer: 1500,
} as const;

/**
 * Animation easings — reframed (P2) to reference the easing tokens above.
 * Upgrades over the old literals: successCheckmark linear→spring (it is a
 * stroke-draw, not a linear pop); tabSwitch ease-in-out→standard.
 */
export const EASING = {
  brandCrossfade: EASING_TOKENS.out,
  errorSlideIn: EASING_TOKENS.out,
  tabSwitch: EASING_TOKENS.standard,
  submitLoading: EASING_TOKENS.standard,
  successCheckmark: EASING_TOKENS.spring,
  skeletonShimmer: 'linear',
} as const;

/** Responsive breakpoints */
export const BREAKPOINTS = {
  mobile: 640,
} as const;

/**
 * Input state colors (relative to theme).
 * borderDefault/borderHover are retained for the legacy --nxp-border-default /
 * --nxp-border-hover aliases but are now driven by the real colorBorder /
 * colorBorderHover AppearanceVariables (see css-properties.ts).
 */
export const INPUT_STATES = {
  borderDefault: '#E5E7EB',
  borderHover: '#D1D5DB',
  borderDarkDefault: '#334155',
  borderDarkHover: '#475569',
  focusRingAlpha: FOCUS_RING_ALPHA.light,
  disabledOpacity: 0.5,
} as const;

/**
 * Soft, navy-tinted layered shadow tokens (16,24,40 not black). The single
 * biggest current gap — zero shadow tokens existed before P2. -lg uses negative
 * spread to keep the blur tight on the floating page card.
 */
export const SHADOW_TOKENS = {
  sm: '0 1px 2px rgba(16, 24, 40, 0.05)',
  card: '0 1px 2px rgba(16, 24, 40, 0.04), 0 1px 3px rgba(16, 24, 40, 0.06)',
  md: '0 1px 3px rgba(16, 24, 40, 0.07), 0 1px 2px rgba(16, 24, 40, 0.06)',
  lg: '0 4px 6px -2px rgba(16, 24, 40, 0.04), 0 12px 16px -4px rgba(16, 24, 40, 0.08)',
} as const;

/**
 * Spacing scale (emitted as derived calc() off the 4px unit so no raw px sneaks
 * into component CSS).
 */
export const SPACING_SCALE = {
  1: 1,
  2: 2,
  3: 3,
  4: 4,
  5: 5,
  6: 6,
  8: 8,
} as const;

/**
 * Type scale (emitted as derived rem off fontSizeBase; 1.25 modular).
 * 12px legal / 13px helper-error / 14px label / 16px input-body /
 * 18px section heading / 24px / 28px total.
 */
export const TYPE_SCALE = {
  xs: '0.75rem',
  sm: '0.8125rem',
  label: '0.875rem',
  base: '1rem',
  lg: '1.125rem',
  xl: '1.5rem',
  '2xl': '1.75rem',
} as const;

/** Line heights. */
export const LINE_HEIGHT = {
  base: '1.5',
  tight: '1.2',
} as const;

/** Radii — inputs/buttons 8px · card-group 12px (one step larger) · chip 4px. */
export const RADII = {
  group: '12px',
  chip: '4px',
} as const;

/** Focus-ring geometry. */
export const FOCUS_RING = {
  width: '3px',
  offset: '2px',
} as const;
