/**
 * Maps Appearance variables to CSS custom properties and injects them into the DOM.
 * All NexusPay custom properties use the `--nxp-` prefix.
 */

import type { Appearance, AppearanceVariables, ThemePreset } from '../types';
import { DEFAULT_VARIABLES, NIGHT_VARIABLES, FLAT_VARIABLES, ANIMATION, INPUT_STATES } from './tokens';

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
};

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

/** Merges default + preset + user variables */
export function resolveVariables(appearance?: Appearance): Required<AppearanceVariables> {
  const preset = resolvePreset(appearance?.theme ?? 'default');
  return {
    ...DEFAULT_VARIABLES,
    ...preset,
    ...(appearance?.variables ?? {}),
  };
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

  // Animation tokens
  lines.push(`  ${PREFIX}-anim-brand-crossfade: ${ANIMATION.brandCrossfade}ms;`);
  lines.push(`  ${PREFIX}-anim-error-slide-in: ${ANIMATION.errorSlideIn}ms;`);
  lines.push(`  ${PREFIX}-anim-tab-switch: ${ANIMATION.tabSwitch}ms;`);
  lines.push(`  ${PREFIX}-anim-submit-loading: ${ANIMATION.submitLoading}ms;`);
  lines.push(`  ${PREFIX}-anim-success-checkmark: ${ANIMATION.successCheckmark}ms;`);
  lines.push(`  ${PREFIX}-anim-skeleton-shimmer: ${ANIMATION.skeletonShimmer}ms;`);

  // Input state tokens
  lines.push(`  ${PREFIX}-border-default: ${INPUT_STATES.borderDefault};`);
  lines.push(`  ${PREFIX}-border-hover: ${INPUT_STATES.borderHover};`);
  lines.push(`  ${PREFIX}-focus-ring-alpha: ${INPUT_STATES.focusRingAlpha};`);
  lines.push(`  ${PREFIX}-disabled-opacity: ${INPUT_STATES.disabledOpacity};`);

  return `:host, :root {\n${lines.join('\n')}\n}`;
}

/** Generates rule overrides as CSS */
export function generateRuleOverrides(rules?: Record<string, Record<string, string>>): string {
  if (!rules) return '';

  return Object.entries(rules)
    .map(([selector, properties]) => {
      const props = Object.entries(properties)
        .map(([prop, value]) => `  ${camelToKebab(prop)}: ${value};`)
        .join('\n');
      return `${selector} {\n${props}\n}`;
    })
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
