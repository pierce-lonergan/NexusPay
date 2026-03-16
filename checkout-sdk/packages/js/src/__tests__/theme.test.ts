import { describe, it, expect } from 'vitest';
import { resolveVariables, generateCSSVariables } from '../theme/css-properties';
import { DEFAULT_VARIABLES, NIGHT_VARIABLES } from '../theme/tokens';

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
});
