import { describe, it, expect } from 'vitest';
import { resolveVariables, generateCSSVariables, generateRuleOverrides, contrastRatio } from '../theme/css-properties';
import { DEFAULT_VARIABLES, NIGHT_VARIABLES } from '../theme/tokens';
import { BASE_STYLES } from '../theme/base-styles';

describe('resolveVariables', () => {
  it('returns defaults when no appearance provided', () => {
    const vars = resolveVariables();
    expect(vars).toEqual(DEFAULT_VARIABLES);
  });

  it('applies night theme overrides', () => {
    const vars = resolveVariables({ theme: 'night' });
    expect(vars.colorPrimary).toBe(NIGHT_VARIABLES.colorPrimary);
    expect(vars.colorBackground).toBe(NIGHT_VARIABLES.colorBackground);
    expect(vars.colorText).toBe(NIGHT_VARIABLES.colorText);
    // Non-overridden values remain defaults
    expect(vars.fontFamily).toBe(DEFAULT_VARIABLES.fontFamily);
  });

  it('applies flat theme overrides', () => {
    const vars = resolveVariables({ theme: 'flat' });
    expect(vars.borderRadius).toBe('0');
    expect(vars.colorPrimary).toBe(DEFAULT_VARIABLES.colorPrimary);
  });

  it('user variables override preset and defaults', () => {
    const vars = resolveVariables({
      theme: 'night',
      variables: { colorPrimary: '#FF0000' },
    });
    expect(vars.colorPrimary).toBe('#FF0000');
    // Night override still applies for other variables
    expect(vars.colorBackground).toBe(NIGHT_VARIABLES.colorBackground);
  });
});

describe('accessibleColorOnColorPrimary safety net (FIX 1b)', () => {
  it('keeps white on the LIGHT default primary (#FFF on #0066FF = 4.83:1 passes)', () => {
    const vars = resolveVariables();
    expect(vars.colorPrimary).toBe('#0066FF');
    expect(vars.onPrimary).toBe('#FFFFFF');
  });

  it('keeps white on the NIGHT primary (#FFF on #2563EB ~5.1:1 passes)', () => {
    const vars = resolveVariables({ theme: 'night' });
    expect(vars.colorPrimary).toBe('#2563EB');
    expect(vars.onPrimary).toBe('#FFFFFF');
  });

  it('auto-flips to a DARK on-primary when a light brand primary fails AA against white (#FACC15)', () => {
    const vars = resolveVariables({ variables: { colorPrimary: '#FACC15' } });
    // White on yellow is ~1.5:1 — must not stay white.
    expect(vars.onPrimary).not.toBe('#FFFFFF');
    // A readable dark ink must contrast >=4.5:1 with the yellow primary.
    expect(contrastRatio(vars.onPrimary, '#FACC15')! >= 4.5).toBe(true);
  });

  it('keeps white on a dark brand primary that passes AA against white', () => {
    const vars = resolveVariables({ variables: { colorPrimary: '#111827' } });
    expect(vars.onPrimary).toBe('#FFFFFF');
  });

  it('respects an explicit merchant onPrimary override even when it fails AA', () => {
    const vars = resolveVariables({
      variables: { colorPrimary: '#FACC15', onPrimary: '#FFFFFF' },
    });
    // Merchant explicitly asked for white — do not auto-flip.
    expect(vars.onPrimary).toBe('#FFFFFF');
  });

  it('emits the derived dark on-primary into the CSS variables string', () => {
    const vars = resolveVariables({ variables: { colorPrimary: '#FACC15' } });
    const css = generateCSSVariables(vars);
    expect(css).toContain(`--nxp-on-primary: ${vars.onPrimary};`);
    expect(css).not.toContain('--nxp-on-primary: #FFFFFF;');
  });
});

describe('contrastRatio (WCAG)', () => {
  it('computes known ratios and returns null for unparseable colors', () => {
    expect(contrastRatio('#FFFFFF', '#000000')).toBeCloseTo(21, 0);
    expect(contrastRatio('#FFFFFF', '#0066FF')!).toBeGreaterThan(4.5);
    expect(contrastRatio('#FFFFFF', '#3B82F6')!).toBeLessThan(4.5);
    expect(contrastRatio('#FFFFFF', 'rebeccapurple')).toBeNull();
  });
});

describe('generateCSSVariables', () => {
  it('generates CSS custom properties string', () => {
    const css = generateCSSVariables(DEFAULT_VARIABLES);
    expect(css).toContain('--nxp-color-primary: #0066FF');
    expect(css).toContain('--nxp-color-background: #FFFFFF');
    expect(css).toContain('--nxp-border-radius: 8px');
    expect(css).toContain('--nxp-font-weight-normal: 400');
    expect(css).toContain(':host, :root {');
  });

  it('includes animation tokens', () => {
    const css = generateCSSVariables(DEFAULT_VARIABLES);
    expect(css).toContain('--nxp-anim-brand-crossfade: 150ms');
    expect(css).toContain('--nxp-anim-error-slide-in: 200ms');
    expect(css).toContain('--nxp-anim-skeleton-shimmer: 1500ms');
  });

  it('includes input state tokens', () => {
    const css = generateCSSVariables(DEFAULT_VARIABLES);
    expect(css).toContain('--nxp-border-default: #E5E7EB');
    expect(css).toContain('--nxp-focus-ring-alpha: 0.15');
  });

  it('emits the new P2 color tokens', () => {
    const css = generateCSSVariables(DEFAULT_VARIABLES);
    expect(css).toContain('--nxp-color-text-secondary: #6B7280');
    expect(css).toContain('--nxp-color-text-placeholder: #9CA3AF');
    expect(css).toContain('--nxp-color-border: #E5E7EB');
    expect(css).toContain('--nxp-color-border-hover: #D1D5DB');
    expect(css).toContain('--nxp-color-warning: #D97706');
    expect(css).toContain('--nxp-color-surface: #FFFFFF');
    expect(css).toContain('--nxp-on-primary: #FFFFFF');
    expect(css).toContain('--nxp-font-weight-medium: 500');
    expect(css).toContain('--nxp-button-border-radius: 8px');
  });

  it('emits the shadow / spacing / type / radii / focus-ring tokens', () => {
    const css = generateCSSVariables(DEFAULT_VARIABLES);
    expect(css).toContain('--nxp-shadow-sm: 0 1px 2px rgba(16, 24, 40, 0.05)');
    expect(css).toContain('--nxp-shadow-card:');
    expect(css).toContain('--nxp-shadow-md:');
    expect(css).toContain('--nxp-shadow-lg:');
    expect(css).toContain('--nxp-space-4: calc(var(--nxp-spacing-unit, 4px) * 4)');
    expect(css).toContain('--nxp-font-size-label: 0.875rem');
    expect(css).toContain('--nxp-font-size-2xl: 1.75rem');
    expect(css).toContain('--nxp-radius-group: 12px');
    expect(css).toContain('--nxp-radius-chip: 4px');
    expect(css).toContain('--nxp-focus-ring-width: 3px');
  });

  it('emits the easing + duration tokens (motion is themeable, not literal)', () => {
    const css = generateCSSVariables(DEFAULT_VARIABLES);
    expect(css).toContain('--nxp-ease-out: cubic-bezier(0.16, 1, 0.3, 1)');
    expect(css).toContain('--nxp-ease-standard: cubic-bezier(0.4, 0, 0.2, 1)');
    expect(css).toContain('--nxp-ease-spring: cubic-bezier(0.34, 1.56, 0.64, 1)');
    expect(css).toContain('--nxp-dur-fast: 150ms');
    expect(css).toContain('--nxp-dur-base: 200ms');
    expect(css).toContain('--nxp-dur-slow: 300ms');
  });
});

describe('BASE_STYLES (P1 focus-ring + tab-tint bug fix)', () => {
  it('uses color-mix and never the invalid rgba(var(...)) form', () => {
    // The bug: rgba() cannot take a CSS custom property / hex as its first arg,
    // so rgba(var(--nxp-color-primary), .15) renders nothing. The fix mirrors
    // the iframe side (card-frame.css) which already uses color-mix.
    expect(BASE_STYLES).toContain('color-mix');
    expect(BASE_STYLES).not.toContain('rgba(var(');
  });

  it('uses the two-layer focus ring (1px solid inner + translucent halo)', () => {
    // The 1px solid inner guarantees >=3:1 for SC 1.4.11.
    expect(BASE_STYLES).toContain('0 0 0 1px var(--nxp-color-primary');
    expect(BASE_STYLES).toContain('color-mix(in srgb, var(--nxp-color-primary, #0066FF) 18%, transparent)');
  });

  it('honors reduced-motion and forced-colors on the parent', () => {
    expect(BASE_STYLES).toContain('@media (prefers-reduced-motion: reduce)');
    expect(BASE_STYLES).toContain('@media (forced-colors: active)');
  });
});

describe('generateRuleOverrides (P3 cosmetic-property allowlist)', () => {
  it('passes through allowed cosmetic properties', () => {
    const css = generateRuleOverrides({
      '.Input': { borderColor: '#ccc', color: '#111', borderRadius: '4px' },
    });
    expect(css).toContain('border-color: #ccc');
    expect(css).toContain('color: #111');
    expect(css).toContain('border-radius: 4px');
  });

  it('drops layout-affecting properties so PCI fields cannot be repositioned', () => {
    const css = generateRuleOverrides({
      '.Input': { position: 'absolute', top: '-9999px', zIndex: '99', display: 'none', color: '#111' },
    });
    expect(css).not.toContain('position');
    expect(css).not.toContain('top:');
    expect(css).not.toContain('z-index');
    expect(css).not.toContain('display');
    // The cosmetic prop still survives.
    expect(css).toContain('color: #111');
  });
});
