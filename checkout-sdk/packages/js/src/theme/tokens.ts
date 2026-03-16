/**
 * Design tokens — the single source of truth for all visual constants.
 * Maps directly to DESIGN.md specifications.
 */

import type { AppearanceVariables } from '../types';

/** Default theme variables (light theme) */
export const DEFAULT_VARIABLES: Required<AppearanceVariables> = {
  colorPrimary: '#0066FF',
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
};

/** Night (dark) theme overrides */
export const NIGHT_VARIABLES: Partial<AppearanceVariables> = {
  colorPrimary: '#3B82F6',
  colorBackground: '#0F172A',
  colorText: '#E2E8F0',
};

/** Flat theme overrides */
export const FLAT_VARIABLES: Partial<AppearanceVariables> = {
  borderRadius: '0',
};

/** Animation durations (ms) */
export const ANIMATION = {
  brandCrossfade: 150,
  errorSlideIn: 200,
  tabSwitch: 200,
  submitLoading: 300,
  successCheckmark: 600,
  skeletonShimmer: 1500,
} as const;

/** Animation easings */
export const EASING = {
  brandCrossfade: 'ease-out',
  errorSlideIn: 'ease-out',
  tabSwitch: 'ease-in-out',
  submitLoading: 'ease',
  successCheckmark: 'linear',
  skeletonShimmer: 'linear',
} as const;

/** Responsive breakpoints */
export const BREAKPOINTS = {
  mobile: 640,
} as const;

/** Input state colors (relative to theme) */
export const INPUT_STATES = {
  borderDefault: '#E5E7EB',
  borderHover: '#D1D5DB',
  borderDarkDefault: '#334155',
  borderDarkHover: '#475569',
  focusRingAlpha: 0.15,
  disabledOpacity: 0.5,
} as const;
