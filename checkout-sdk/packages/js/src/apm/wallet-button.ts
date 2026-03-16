/**
 * Shared utility for rendering Apple Pay / Google Pay buttons
 * with consistent styling per brand guidelines.
 */

export interface WalletButtonStyle {
  width?: string;
  minHeight?: string;
  borderRadius?: string;
}

const DEFAULT_STYLE: Required<WalletButtonStyle> = {
  width: '100%',
  minHeight: '44px',
  borderRadius: '8px',
};

/**
 * Wraps a wallet button in a container with consistent styling.
 */
export function wrapWalletButton(
  button: HTMLElement,
  style?: WalletButtonStyle,
): HTMLElement {
  const wrapper = document.createElement('div');
  wrapper.className = 'WalletButton';

  const merged = { ...DEFAULT_STYLE, ...style };

  Object.assign(wrapper.style, {
    width: merged.width,
    minHeight: merged.minHeight,
  });

  // Apply consistent sizing to the button
  Object.assign(button.style, {
    width: '100%',
    minHeight: merged.minHeight,
    borderRadius: merged.borderRadius,
  });

  wrapper.appendChild(button);
  return wrapper;
}

/**
 * Creates a divider element ("or pay with") for between wallet and card sections.
 */
export function createDivider(text = 'Or pay with'): HTMLElement {
  const divider = document.createElement('div');
  Object.assign(divider.style, {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    margin: '16px 0',
    color: 'var(--nxp-color-text, #1A1A2E)',
    opacity: '0.5',
    fontSize: '0.8125rem',
  });

  const line1 = document.createElement('div');
  Object.assign(line1.style, {
    flex: '1',
    height: '1px',
    backgroundColor: 'var(--nxp-border-default, #E5E7EB)',
  });

  const label = document.createElement('span');
  label.textContent = text;

  const line2 = document.createElement('div');
  Object.assign(line2.style, {
    flex: '1',
    height: '1px',
    backgroundColor: 'var(--nxp-border-default, #E5E7EB)',
  });

  divider.appendChild(line1);
  divider.appendChild(label);
  divider.appendChild(line2);

  return divider;
}
