/**
 * Maps Appearance variables to CSS custom properties and injects them into the DOM.
 * All NexusPay custom properties use the `--nxp-` prefix.
 */

import type { Appearance, AppearanceVariables, ThemePreset } from '../types';
import {
  DEFAULT_VARIABLES,
  NIGHT_VARIABLES,
  FLAT_VARIABLES,
  ANIMATION,
  EASING,
  EASING_TOKENS,
  DURATION_TOKENS,
  INPUT_STATES,
  SHADOW_TOKENS,
  SPACING_SCALE,
  TYPE_SCALE,
  LINE_HEIGHT,
  RADII,
  FOCUS_RING,
} from './tokens';

/** CSS custom property prefix */
const PREFIX = '--nxp';

/** Maps an AppearanceVariables key to its CSS custom property name */
const VARIABLE_MAP: Record<keyof AppearanceVariables, string> = {
  colorPrimary: `${PREFIX}-color-primary`,
  colorBackground: `${PREFIX}-color-background`,
  colorText: `${PREFIX}-color-text`,
  colorDanger: `${PREFIX}-color-danger`,
  colorSuccess: `${PREFIX}-color-success`,
  fontFamily: `${PREFIX}-font-family`,
  fontSizeBase: `${PREFIX}-font-size-base`,
  borderRadius: `${PREFIX}-border-radius`,
  spacingUnit: `${PREFIX}-spacing-unit`,
  fontWeightNormal: `${PREFIX}-font-weight-normal`,
  fontWeightBold: `${PREFIX}-font-weight-bold`,
  // --- P2: extended token set ---
  colorTextSecondary: `${PREFIX}-color-text-secondary`,
  colorTextPlaceholder: `${PREFIX}-color-text-placeholder`,
  colorBorder: `${PREFIX}-color-border`,
  colorBorderHover: `${PREFIX}-color-border-hover`,
  colorWarning: `${PREFIX}-color-warning`,
  colorSurface: `${PREFIX}-color-surface`,
  onPrimary: `${PREFIX}-on-primary`,
  fontWeightMedium: `${PREFIX}-font-weight-medium`,
  buttonBorderRadius: `${PREFIX}-button-border-radius`,
};

/**
 * Per-selector cosmetic-property allowlist for merchant `rules`. Mirrors
 * Stripe's `.Input` allowlist. Anything outside this set (position, display,
 * z-index, content, top/left, transform-origin hacks) is silently dropped so a
 * malicious merchant rule cannot reposition or hide the PCI iframe inputs.
 * Security behavior only — no token/handshake change.
 */
const ALLOWED_RULE_PROPERTIES = new Set<string>([
  'background-color',
  'background',
  'border',
  'border-color',
  'border-width',
  'border-style',
  'border-radius',
  'box-shadow',
  'color',
  'font-family',
  'font-size',
  'font-weight',
  'font-style',
  'font-variant',
  'font-variant-numeric',
  'letter-spacing',
  'line-height',
  'margin',
  'margin-top',
  'margin-right',
  'margin-bottom',
  'margin-left',
  'padding',
  'padding-top',
  'padding-right',
  'padding-bottom',
  'padding-left',
  'outline',
  'outline-color',
  'outline-style',
  'outline-width',
  'outline-offset',
  'text-decoration',
  'text-shadow',
  'text-transform',
  'transition',
  '-webkit-font-smoothing',
  '-webkit-text-fill-color',
  '-webkit-box-shadow',
]);

/** Resolves theme preset to its variable overrides */
function resolvePreset(theme: ThemePreset): Partial<AppearanceVariables> {
  switch (theme) {
    case 'night': return NIGHT_VARIABLES;
    case 'flat': return FLAT_VARIABLES;
    case 'none': return {};
    case 'default':
    default: return {};
  }
}

/** White, used both as the default button label and the contrast reference. */
const WHITE = '#FFFFFF';
/**
 * Dark "ink" fallback for the button label when the brand primary is too light
 * for white text. Near-black keeps a conventional readable label on any pale
 * brand color (e.g. a yellow primary).
 */
const DARK_INK = '#0A0A0A';
/** WCAG AA contrast threshold for normal-size (16px) / non-large text. */
const AA_CONTRAST = 4.5;

/**
 * Parses a #RGB / #RRGGBB hex string to [r,g,b] in 0..255. Returns null for any
 * value we cannot confidently parse (named colors, rgb()/hsl(), color-mix, etc.)
 * — callers then leave the configured on-primary untouched rather than guess.
 */
function parseHexColor(value: string): [number, number, number] | null {
  if (typeof value !== 'string') return null;
  const hex = value.trim().replace(/^#/, '');
  if (/^[0-9a-fA-F]{3}$/.test(hex)) {
    const r = parseInt(hex[0] + hex[0], 16);
    const g = parseInt(hex[1] + hex[1], 16);
    const b = parseInt(hex[2] + hex[2], 16);
    return [r, g, b];
  }
  if (/^[0-9a-fA-F]{6}$/.test(hex)) {
    const r = parseInt(hex.slice(0, 2), 16);
    const g = parseInt(hex.slice(2, 4), 16);
    const b = parseInt(hex.slice(4, 6), 16);
    return [r, g, b];
  }
  return null;
}

/** Relative luminance per WCAG 2.x (sRGB → linearized, weighted). */
function relativeLuminance([r, g, b]: [number, number, number]): number {
  const channel = (c: number): number => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

/**
 * WCAG contrast ratio (1..21) between two hex colors. Returns null when either
 * color cannot be parsed as hex (so the caller can fail-safe to the configured
 * value instead of mis-deriving).
 */
export function contrastRatio(a: string, b: string): number | null {
  const ca = parseHexColor(a);
  const cb = parseHexColor(b);
  if (!ca || !cb) return null;
  const la = relativeLuminance(ca);
  const lb = relativeLuminance(cb);
  const lighter = Math.max(la, lb);
  const darker = Math.min(la, lb);
  return (lighter + 0.05) / (darker + 0.05);
}

/**
 * Safety net promised by the spec (`accessibleColorOnColorPrimary`). Given the
 * resolved variables and the merchant's RAW appearance, returns the button-label
 * color to actually emit for `--nxp-on-primary`.
 *
 * Rule: when on-primary would otherwise be the DEFAULT white (the merchant did
 * NOT explicitly set onPrimary) AND white fails AA 4.5:1 against the resolved
 * colorPrimary, auto-flip to a dark ink so a too-light brand color still gets a
 * readable label. An explicit merchant onPrimary override is always respected.
 * If colorPrimary isn't a parseable hex, we leave white untouched (no guessing).
 */
export function accessibleColorOnColorPrimary(
  variables: Required<AppearanceVariables>,
  appearance?: Appearance,
): string {
  const configured = variables.onPrimary;
  // Respect an explicit merchant override — only auto-derive the default white.
  const merchantSetOnPrimary = appearance?.variables?.onPrimary !== undefined;
  if (merchantSetOnPrimary || configured !== WHITE) {
    return configured;
  }
  const ratio = contrastRatio(WHITE, variables.colorPrimary);
  if (ratio !== null && ratio < AA_CONTRAST) {
    // White-on-primary fails AA. Prefer the configured text ink if it reads
    // better on the primary; otherwise fall back to the near-black ink.
    const inkRatio = contrastRatio(variables.colorText, variables.colorPrimary);
    if (inkRatio !== null && inkRatio >= AA_CONTRAST) {
      return variables.colorText;
    }
    return DARK_INK;
  }
  return configured;
}

/** Merges default + preset + user variables */
export function resolveVariables(appearance?: Appearance): Required<AppearanceVariables> {
  const preset = resolvePreset(appearance?.theme ?? 'default');
  const merged: Required<AppearanceVariables> = {
    ...DEFAULT_VARIABLES,
    ...preset,
    ...(appearance?.variables ?? {}),
  };
  // Safety net: auto-flip a default-white button label to a dark ink when the
  // resolved brand primary is too light for white text (WCAG AA 4.5:1).
  merged.onPrimary = accessibleColorOnColorPrimary(merged, appearance);
  return merged;
}

/** Generates a CSS string of custom property declarations */
export function generateCSSVariables(variables: Required<AppearanceVariables>): string {
  const lines: string[] = [];

  for (const [key, cssVar] of Object.entries(VARIABLE_MAP)) {
    const value = variables[key as keyof AppearanceVariables];
    if (value !== undefined) {
      lines.push(`  ${cssVar}: ${value};`);
    }
  }

  // buttonBorderRadius falls back to borderRadius when not explicitly set.
  // (Both are always present in Required<AppearanceVariables>, but keep the
  // semantic intent documented.)

  // Animation duration tokens
  lines.push(`  ${PREFIX}-anim-brand-crossfade: ${ANIMATION.brandCrossfade}ms;`);
  lines.push(`  ${PREFIX}-anim-error-slide-in: ${ANIMATION.errorSlideIn}ms;`);
  lines.push(`  ${PREFIX}-anim-tab-switch: ${ANIMATION.tabSwitch}ms;`);
  lines.push(`  ${PREFIX}-anim-submit-loading: ${ANIMATION.submitLoading}ms;`);
  lines.push(`  ${PREFIX}-anim-success-checkmark: ${ANIMATION.successCheckmark}ms;`);
  lines.push(`  ${PREFIX}-anim-skeleton-shimmer: ${ANIMATION.skeletonShimmer}ms;`);

  // Easing tokens (referenceable so the iframe & parent stay in lockstep)
  lines.push(`  ${PREFIX}-ease-out: ${EASING_TOKENS.out};`);
  lines.push(`  ${PREFIX}-ease-standard: ${EASING_TOKENS.standard};`);
  lines.push(`  ${PREFIX}-ease-in: ${EASING_TOKENS.in};`);
  lines.push(`  ${PREFIX}-ease-spring: ${EASING_TOKENS.spring};`);

  // Per-interaction easing aliases (reframed from the old literal EASING map)
  lines.push(`  ${PREFIX}-ease-brand-crossfade: ${EASING.brandCrossfade};`);
  lines.push(`  ${PREFIX}-ease-error-slide-in: ${EASING.errorSlideIn};`);
  lines.push(`  ${PREFIX}-ease-tab-switch: ${EASING.tabSwitch};`);
  lines.push(`  ${PREFIX}-ease-submit-loading: ${EASING.submitLoading};`);
  lines.push(`  ${PREFIX}-ease-success-checkmark: ${EASING.successCheckmark};`);

  // Duration tokens
  lines.push(`  ${PREFIX}-dur-instant: ${DURATION_TOKENS.instant}ms;`);
  lines.push(`  ${PREFIX}-dur-fast: ${DURATION_TOKENS.fast}ms;`);
  lines.push(`  ${PREFIX}-dur-base: ${DURATION_TOKENS.base}ms;`);
  lines.push(`  ${PREFIX}-dur-slow: ${DURATION_TOKENS.slow}ms;`);

  // Spacing scale (derived off the spacing unit so nothing escapes the 4px grid)
  for (const [step, mult] of Object.entries(SPACING_SCALE)) {
    lines.push(`  ${PREFIX}-space-${step}: calc(var(${PREFIX}-spacing-unit, 4px) * ${mult});`);
  }
  lines.push(`  ${PREFIX}-grid-row-spacing: var(${PREFIX}-space-4);`);
  lines.push(`  ${PREFIX}-grid-column-spacing: var(${PREFIX}-space-3);`);

  // Type scale
  lines.push(`  ${PREFIX}-font-size-xs: ${TYPE_SCALE.xs};`);
  lines.push(`  ${PREFIX}-font-size-sm: ${TYPE_SCALE.sm};`);
  lines.push(`  ${PREFIX}-font-size-label: ${TYPE_SCALE.label};`);
  lines.push(`  ${PREFIX}-font-size-lg: ${TYPE_SCALE.lg};`);
  lines.push(`  ${PREFIX}-font-size-xl: ${TYPE_SCALE.xl};`);
  lines.push(`  ${PREFIX}-font-size-2xl: ${TYPE_SCALE['2xl']};`);
  lines.push(`  ${PREFIX}-font-line-height: ${LINE_HEIGHT.base};`);
  lines.push(`  ${PREFIX}-font-line-height-tight: ${LINE_HEIGHT.tight};`);

  // Radii
  lines.push(`  ${PREFIX}-radius-group: ${RADII.group};`);
  lines.push(`  ${PREFIX}-radius-chip: ${RADII.chip};`);

  // Shadow / elevation tokens (navy-tinted, layered)
  lines.push(`  ${PREFIX}-shadow-sm: ${SHADOW_TOKENS.sm};`);
  lines.push(`  ${PREFIX}-shadow-card: ${SHADOW_TOKENS.card};`);
  lines.push(`  ${PREFIX}-shadow-md: ${SHADOW_TOKENS.md};`);
  lines.push(`  ${PREFIX}-shadow-lg: ${SHADOW_TOKENS.lg};`);

  // Focus-ring geometry
  lines.push(`  ${PREFIX}-focus-ring-width: ${FOCUS_RING.width};`);
  lines.push(`  ${PREFIX}-focus-outline-offset: ${FOCUS_RING.offset};`);

  // Input state tokens.
  // --nxp-border-default / --nxp-border-hover are kept as aliases of the new,
  // merchant-overridable colorBorder / colorBorderHover so existing CSS that
  // references the old names keeps working.
  lines.push(`  ${PREFIX}-border-default: ${variables.colorBorder ?? INPUT_STATES.borderDefault};`);
  lines.push(`  ${PREFIX}-border-hover: ${variables.colorBorderHover ?? INPUT_STATES.borderHover};`);
  lines.push(`  ${PREFIX}-focus-ring-alpha: ${INPUT_STATES.focusRingAlpha};`);
  lines.push(`  ${PREFIX}-disabled-opacity: ${INPUT_STATES.disabledOpacity};`);

  return `:host, :root {\n${lines.join('\n')}\n}`;
}

/**
 * Generates rule overrides as CSS. Each property is filtered against
 * ALLOWED_RULE_PROPERTIES (cosmetic-only); non-cosmetic / layout-affecting
 * props are dropped so a merchant rule cannot reposition or hide PCI fields.
 */
export function generateRuleOverrides(rules?: Record<string, Record<string, string>>): string {
  if (!rules) return '';

  return Object.entries(rules)
    .map(([selector, properties]) => {
      const props = Object.entries(properties)
        .map(([prop, value]) => [camelToKebab(prop), value] as const)
        .filter(([prop]) => ALLOWED_RULE_PROPERTIES.has(prop))
        .map(([prop, value]) => `  ${prop}: ${value};`)
        .join('\n');
      if (!props) return '';
      return `${selector} {\n${props}\n}`;
    })
    .filter(Boolean)
    .join('\n\n');
}

/** Creates and injects a <style> element with theme CSS */
export function injectThemeStyles(appearance?: Appearance, container?: HTMLElement): HTMLStyleElement {
  const variables = resolveVariables(appearance);
  const cssVars = generateCSSVariables(variables);
  const ruleOverrides = generateRuleOverrides(appearance?.rules);

  const style = document.createElement('style');
  style.setAttribute('data-nexuspay-theme', 'true');
  style.textContent = `${cssVars}\n\n${ruleOverrides}`;

  (container ?? document.head).appendChild(style);
  return style;
}

function camelToKebab(str: string): string {
  return str.replace(/[A-Z]/g, m => `-${m.toLowerCase()}`);
}
