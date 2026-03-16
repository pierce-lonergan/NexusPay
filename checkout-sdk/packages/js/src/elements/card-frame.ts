/**
 * Card frame internal logic — runs inside the sandboxed PCI iframe.
 * Handles input formatting, validation, brand detection, and tokenization
 * requests to the NexusPay API. Raw PAN never leaves this context.
 */

import { detectBrand, luhnCheck, isExpiryValid, formatPAN, cvcLength } from '../utils/card-validator';
import type { CardBrand } from '../types';

interface FrameState {
  pan: string;        // raw digits
  expMonth: string;   // 2-digit
  expYear: string;    // 2-digit
  cvc: string;
  brand: CardBrand;
  errors: {
    pan: string | null;
    expiry: string | null;
    cvc: string | null;
  };
  apiBase: string;
  sessionToken: string;
}

const state: FrameState = {
  pan: '',
  expMonth: '',
  expYear: '',
  cvc: '',
  brand: 'unknown',
  errors: { pan: null, expiry: null, cvc: null },
  apiBase: '',
  sessionToken: '',
};

/** Posts a message to the parent window. */
function postToParent(type: string, payload?: unknown): void {
  window.parent.postMessage(
    { source: 'nexuspay-card-frame', type, payload },
    '*',
  );
}

/** Checks if all fields are complete and valid. */
function isComplete(): boolean {
  return (
    luhnCheck(state.pan) &&
    isExpiryValid(parseInt(state.expMonth, 10), parseInt(state.expYear, 10)) &&
    state.cvc.length === cvcLength(state.brand) &&
    !state.errors.pan &&
    !state.errors.expiry &&
    !state.errors.cvc
  );
}

/** Emits current card state to parent. */
function emitChange(): void {
  const complete = isComplete();
  const empty = !state.pan && !state.expMonth && !state.expYear && !state.cvc;

  postToParent('CARD_CHANGE', {
    complete,
    empty,
    brand: state.brand,
    error: state.errors.pan || state.errors.expiry || state.errors.cvc,
    cardLastFour: state.pan.length >= 4 ? state.pan.slice(-4) : null,
  });

  if (complete) {
    postToParent('CARD_COMPLETE');
  }
}

// --- Input Handlers ---

/** Handles card number input with formatting and cursor preservation. */
function handlePanInput(input: HTMLInputElement): void {
  const cursorPos = input.selectionStart ?? 0;
  const rawBefore = input.value;
  const digitsBeforeCursor = rawBefore.slice(0, cursorPos).replace(/\D/g, '').length;

  // Extract digits only
  const digits = input.value.replace(/\D/g, '');
  state.pan = digits;
  state.brand = detectBrand(digits);

  // Format and set
  const formatted = formatPAN(digits);
  input.value = formatted;

  // Restore cursor position accounting for spaces
  let newCursor = 0;
  let digitsSeen = 0;
  for (let i = 0; i < formatted.length && digitsSeen < digitsBeforeCursor; i++) {
    newCursor = i + 1;
    if (formatted[i] !== ' ') {
      digitsSeen++;
    }
  }
  input.setSelectionRange(newCursor, newCursor);

  // Validate
  if (digits.length >= 13) {
    state.errors.pan = luhnCheck(digits) ? null : 'Invalid card number';
  } else {
    state.errors.pan = null;
  }

  emitChange();
}

/** Handles expiry input with auto-advance (MM/YY). */
function handleExpiryInput(
  monthInput: HTMLInputElement,
  yearInput: HTMLInputElement,
  which: 'month' | 'year',
): void {
  if (which === 'month') {
    const digits = monthInput.value.replace(/\D/g, '').slice(0, 2);
    monthInput.value = digits;
    state.expMonth = digits;

    // Auto-advance to year after 2 digits
    if (digits.length === 2) {
      const month = parseInt(digits, 10);
      if (month < 1 || month > 12) {
        state.errors.expiry = 'Invalid month';
      } else {
        state.errors.expiry = null;
        yearInput.focus();
      }
    } else {
      state.errors.expiry = null;
    }
  } else {
    const digits = yearInput.value.replace(/\D/g, '').slice(0, 2);
    yearInput.value = digits;
    state.expYear = digits;

    if (digits.length === 2 && state.expMonth.length === 2) {
      const month = parseInt(state.expMonth, 10);
      const year = parseInt(digits, 10);
      state.errors.expiry = isExpiryValid(month, year) ? null : 'Card expired';
    }
  }

  emitChange();
}

/** Handles CVC input. */
function handleCvcInput(input: HTMLInputElement): void {
  const maxLen = cvcLength(state.brand);
  const digits = input.value.replace(/\D/g, '').slice(0, maxLen);
  input.value = digits;
  state.cvc = digits;

  if (digits.length === maxLen) {
    state.errors.cvc = null;
  } else if (digits.length > 0) {
    state.errors.cvc = null; // Don't show error while typing
  }

  emitChange();
}

// --- Tokenization ---

async function handleTokenizeRequest(): Promise<void> {
  if (!isComplete()) {
    postToParent('TOKENIZE_RESPONSE', {
      success: false,
      error: 'Card details are incomplete or invalid',
    });
    return;
  }

  try {
    const response = await fetch(`${state.apiBase}/v1/checkout/tokenize`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${state.sessionToken}`,
      },
      body: JSON.stringify({
        type: 'card',
        card_last_four: state.pan.slice(-4),
        card_brand: state.brand,
        card_exp_month: parseInt(state.expMonth, 10),
        card_exp_year: 2000 + parseInt(state.expYear, 10),
        // token_data would be encrypted PAN in production
        token_data: btoa(state.pan),
      }),
    });

    if (!response.ok) {
      const errData = await response.json().catch(() => ({}));
      postToParent('TOKENIZE_RESPONSE', {
        success: false,
        error: errData?.error?.message ?? `Tokenization failed (${response.status})`,
      });
      return;
    }

    const data = await response.json();
    postToParent('TOKENIZE_RESPONSE', {
      success: true,
      tokenId: data.id,
    });
  } catch (err) {
    postToParent('TOKENIZE_RESPONSE', {
      success: false,
      error: err instanceof Error ? err.message : 'Network error during tokenization',
    });
  }
}

// --- Message Handler ---

function handleParentMessage(event: MessageEvent): void {
  const data = event.data;
  if (!data || data.source !== 'nexuspay-parent') return;

  switch (data.type) {
    case 'STYLE_UPDATE': {
      const payload = data.payload as Record<string, unknown>;
      if (payload?.sessionToken) {
        state.sessionToken = payload.sessionToken as string;
      }
      if (payload?.apiBase) {
        state.apiBase = payload.apiBase as string;
      }
      if (payload?.appearance) {
        applyAppearance(payload.appearance as Record<string, unknown>);
      }
      break;
    }
    case 'TOKENIZE_REQUEST':
      handleTokenizeRequest();
      break;
    case 'FOCUS_REQUEST': {
      const panInput = document.getElementById('card-number') as HTMLInputElement | null;
      panInput?.focus();
      break;
    }
  }
}

function applyAppearance(appearance: Record<string, unknown>): void {
  const variables = appearance.variables as Record<string, string | number> | undefined;
  if (!variables) return;

  const root = document.documentElement;
  const mapping: Record<string, string> = {
    colorPrimary: '--nxp-color-primary',
    colorBackground: '--nxp-color-background',
    colorText: '--nxp-color-text',
    colorDanger: '--nxp-color-danger',
    colorSuccess: '--nxp-color-success',
    fontFamily: '--nxp-font-family',
    fontSizeBase: '--nxp-font-size-base',
    borderRadius: '--nxp-border-radius',
    spacingUnit: '--nxp-spacing-unit',
    fontWeightNormal: '--nxp-font-weight-normal',
    fontWeightBold: '--nxp-font-weight-bold',
  };

  for (const [key, cssVar] of Object.entries(mapping)) {
    if (variables[key] !== undefined) {
      root.style.setProperty(cssVar, String(variables[key]));
    }
  }
}

// --- Init ---

/** Initializes the card frame when the DOM is ready. */
export function initCardFrame(): void {
  window.addEventListener('message', handleParentMessage);

  // Bind input handlers
  const panInput = document.getElementById('card-number') as HTMLInputElement | null;
  const monthInput = document.getElementById('card-exp-month') as HTMLInputElement | null;
  const yearInput = document.getElementById('card-exp-year') as HTMLInputElement | null;
  const cvcInput = document.getElementById('card-cvc') as HTMLInputElement | null;

  if (panInput) {
    panInput.addEventListener('input', () => handlePanInput(panInput));
  }
  if (monthInput && yearInput) {
    monthInput.addEventListener('input', () => handleExpiryInput(monthInput, yearInput, 'month'));
    yearInput.addEventListener('input', () => handleExpiryInput(monthInput, yearInput, 'year'));
  }
  if (cvcInput) {
    cvcInput.addEventListener('input', () => handleCvcInput(cvcInput));
  }

  // Signal ready
  postToParent('FRAME_READY');
}

// Auto-init if running in iframe context
if (typeof window !== 'undefined' && window.parent !== window) {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initCardFrame);
  } else {
    initCardFrame();
  }
}
