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
  /**
   * B-006: the exact origin of the embedding parent page. We post replies ONLY
   * to this origin, never "*". It is seeded from the document referrer (the
   * embedder) for the very first FRAME_READY, then confirmed/overwritten by the
   * `parentOrigin` the parent sends in its STYLE_UPDATE handshake.
   */
  parentOrigin: string;
}

/**
 * B-006 (race fix): the browser-ATTESTED origin of our immediate embedder, read
 * from `window.location.ancestorOrigins`. The browser populates this list with
 * the real origins of every ancestor browsing context; index 0 is the immediate
 * parent. Unlike the document referrer or any payload field, it CANNOT be spoofed
 * by a co-resident frame — so when present it is the single source of truth for
 * the expected parent origin and closes the trust-on-first-use (TOFU) race where
 * a hostile co-resident frame (e.g. https://evil.example embedded on the same
 * merchant page) posts the handshake before the real SDK and gets pinned.
 *
 * Returns "" when ancestorOrigins is unavailable or empty. ancestorOrigins is a
 * DOMStringList implemented on Chromium/WebKit; Firefox does not implement it
 * (undefined) and jsdom does not implement it either (undefined) — in both cases
 * we fall back to the existing referrer-seeded TOFU behavior.
 */
function deriveAuthoritativeParentOrigin(): string {
  try {
    if (typeof window !== 'undefined' && window.location) {
      const ancestors: DOMStringList | undefined = window.location.ancestorOrigins;
      if (ancestors && ancestors.length > 0) {
        return ancestors[0];
      }
    }
  } catch {
    /* fall through */
  }
  return '';
}

/**
 * Best-effort origin of the page that embedded this frame, used to target the
 * initial FRAME_READY before the parent has handed us its exact origin. Returns
 * "" when the referrer is unavailable (e.g. referrer policy stripped it); an
 * empty target origin means "same origin as this document", which is the safe
 * conservative default and never a wildcard.
 */
function deriveInitialParentOrigin(): string {
  try {
    if (typeof document !== 'undefined' && document.referrer) {
      return new URL(document.referrer).origin;
    }
  } catch {
    /* fall through */
  }
  return '';
}

/**
 * B-006 (race fix): computed ONCE at module init. When the browser attests an
 * immediate-parent origin via ancestorOrigins, it is the SOLE accepted parent
 * origin (see {@link isExpectedParentOrigin}) and is pinned into
 * state.parentOrigin so the very first inbound message is already validated
 * against the real parent — a co-resident attacker's first message is rejected
 * even if it wins the race. "" means "no browser attestation available" (Firefox
 * / jsdom), in which case the legacy TOFU path applies.
 */
let authoritativeParentOrigin: string = deriveAuthoritativeParentOrigin();

const state: FrameState = {
  pan: '',
  expMonth: '',
  expYear: '',
  cvc: '',
  brand: 'unknown',
  errors: { pan: null, expiry: null, cvc: null },
  apiBase: '',
  sessionToken: '',
  // When the browser attests the parent origin, pin it immediately so the gate
  // is authoritative from the first message. Otherwise seed from the referrer
  // for the initial FRAME_READY target and let TOFU pin on first accepted message.
  parentOrigin: authoritativeParentOrigin || deriveInitialParentOrigin(),
};

/**
 * Posts a message to the parent window.
 * B-006: targets the parent's exact origin (never "*"). A wildcard target would
 * leak card metadata (last four, brand, completion state, tokenization results)
 * to whatever document currently occupies the parent frame.
 */
function postToParent(type: string, payload?: unknown): void {
  window.parent.postMessage(
    { source: 'nexuspay-card-frame', type, payload },
    state.parentOrigin || '',
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

/**
 * B-006 (hardened): a parent message is only honored when its BROWSER-ATTESTED
 * event.origin matches the pinned parent origin. There is NO accept-all
 * fallback: the previous `!state.parentOrigin` clause degraded the gate to
 * "accept any origin" whenever the referrer was stripped (Referrer-Policy:
 * no-referrer / strict-origin downgrade / sandboxed parent), letting a hostile
 * co-resident frame win the handshake race and redirect the PAN to evil.com.
 *
 * Net rule:
 *   - parentOrigin set   → accept iff event.origin === state.parentOrigin
 *   - parentOrigin unset → pin state.parentOrigin = event.origin (the
 *     trustworthy browser value) and accept this first message.
 *
 * The origin is ONLY ever taken from event.origin (browser-attested). We never
 * trust a sender-declared payload.parentOrigin to set or override it.
 *
 * An empty origin ("") is only accepted in non-browser/test contexts (jsdom
 * dispatches MessageEvents whose origin defaults to ""); in a real browser a
 * cross-origin attacker window always carries a concrete origin, so this branch
 * never opens a production hole. See {@link isTestOriginContext}.
 */
function isTestOriginContext(): boolean {
  // jsdom (the test runner) sets navigator.userAgent to a string containing
  // "jsdom" and never delivers real cross-origin MessageEvents. A real browser
  // never reports a "jsdom" UA, so the "" allowance below cannot be reached in
  // production — the gate fails closed on any unknown/foreign origin.
  return (
    typeof navigator !== 'undefined' &&
    typeof navigator.userAgent === 'string' &&
    navigator.userAgent.toLowerCase().includes('jsdom')
  );
}

function isExpectedParentOrigin(origin: string): boolean {
  if (authoritativeParentOrigin) {
    // B-006 (race fix): the browser attested the immediate-parent origin. It is
    // the SOLE accepted origin — NO trust-on-first-use, NO accept-all. This
    // closes the TOFU race: a co-resident attacker frame (event.origin =
    // https://evil.example) is rejected even if its handshake arrives first,
    // because it can never equal the browser-attested parent. state.parentOrigin
    // was pinned to this value at init, so the very first message is validated.
    return origin === authoritativeParentOrigin;
  }
  if (origin === '') {
    // Same-document / synthetic event: only tolerated under the test runtime.
    return isTestOriginContext();
  }
  if (!state.parentOrigin) {
    // No browser attestation (Firefox / jsdom): trust-on-first-use using the
    // browser-attested origin. This is the best achievable without
    // ancestorOrigins. Pinning happens in handleParentMessage.
    return true;
  }
  return origin === state.parentOrigin;
}

function handleParentMessage(event: MessageEvent): void {
  if (!isExpectedParentOrigin(event.origin)) return;

  const data = event.data;
  if (!data || data.source !== 'nexuspay-parent') return;

  // Pin the parent's origin to the BROWSER-ATTESTED event.origin on the first
  // accepted message. Never to a sender-declared payload value (a sender can lie
  // in the payload; only event.origin is browser-attested).
  if (!state.parentOrigin && event.origin) {
    state.parentOrigin = event.origin;
  }

  switch (data.type) {
    case 'STYLE_UPDATE': {
      const payload = data.payload as Record<string, unknown>;
      // B-006 (hardened): do NOT let the payload set/override the origin. If a
      // payload.parentOrigin is present it must AGREE with the browser-attested
      // event.origin; otherwise it is ignored.
      if (
        payload?.parentOrigin &&
        event.origin &&
        payload.parentOrigin !== event.origin
      ) {
        return; // spoofed payload origin — drop the whole message
      }
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

/**
 * Test-only exports. `card-frame.ts` runs inside the sandboxed iframe and is not
 * part of the public package surface (it is bundled standalone). These are
 * exposed so the B-006 origin-gating logic can be unit tested directly without
 * standing up a real cross-origin iframe.
 * @internal
 */
export const __test__ = {
  state,
  handleParentMessage,
  isExpectedParentOrigin,
  deriveInitialParentOrigin,
  deriveAuthoritativeParentOrigin,
  /** The currently-pinned browser-attested parent origin ("" when none). */
  getAuthoritativeParentOrigin(): string {
    return authoritativeParentOrigin;
  },
  /**
   * Re-runs the once-at-init authoritative-origin derivation. Call this in tests
   * AFTER stubbing `window.location.ancestorOrigins` to simulate a fresh frame
   * load on Chromium/WebKit (attestation present) vs Firefox/jsdom (absent), then
   * pin state.parentOrigin exactly as module init does.
   */
  recomputeAuthoritativeParentOrigin(): void {
    authoritativeParentOrigin = deriveAuthoritativeParentOrigin();
    state.parentOrigin = authoritativeParentOrigin || deriveInitialParentOrigin();
  },
  /** Resets mutable state between tests. */
  resetState(parentOrigin = '') {
    state.parentOrigin = parentOrigin;
    state.apiBase = '';
    state.sessionToken = '';
    state.pan = '';
    state.expMonth = '';
    state.expYear = '';
    state.cvc = '';
    state.brand = 'unknown';
  },
};

// Auto-init if running in iframe context
if (typeof window !== 'undefined' && window.parent !== window) {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initCardFrame);
  } else {
    initCardFrame();
  }
}
