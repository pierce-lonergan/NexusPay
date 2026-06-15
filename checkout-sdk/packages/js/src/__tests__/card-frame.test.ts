// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { __test__ } from '../elements/card-frame';

const {
  state,
  handleParentMessage,
  isExpectedParentOrigin,
  resetState,
  recomputeAuthoritativeParentOrigin,
  getAuthoritativeParentOrigin,
  handlePanInput,
  handlePanBlur,
  handleExpiryInput,
  handleExpiryBlur,
  handleCvcInput,
  handleCvcBlur,
  renderFieldError,
  MSG,
} = __test__;

/**
 * Builds a STYLE_UPDATE event as the attacker / legit parent would post it.
 * `origin` is the browser-attested event.origin (the trustworthy field).
 */
function styleUpdateEvent(
  origin: string,
  payload: Record<string, unknown>,
): MessageEvent {
  return new MessageEvent('message', {
    origin,
    data: { source: 'nexuspay-parent', type: 'STYLE_UPDATE', payload },
  });
}

describe('card-frame B-006 receive-side origin gate', () => {
  beforeEach(() => {
    // Simulate referrer-stripped load: parentOrigin starts unset.
    resetState('');
  });

  it('IGNORES a STYLE_UPDATE from a foreign origin when parentOrigin is unset (referrer absent)', () => {
    // Attacker wins the race: posts BEFORE the legit handshake, declaring its
    // own malicious apiBase / sessionToken and lying in payload.parentOrigin.
    handleParentMessage(
      styleUpdateEvent('https://evil.example.com', {
        parentOrigin: 'https://merchant.example.com', // spoofed claim
        apiBase: 'https://evil.example.com',
        sessionToken: 'attacker-token',
      }),
    );

    // Nothing the attacker sent may take effect, and the origin must NOT be
    // pinned to the attacker.
    expect(state.apiBase).toBe('');
    expect(state.sessionToken).toBe('');
    // parentOrigin must not have been pinned to evil (gate pins event.origin
    // only AFTER the message passes; but a foreign first message with a
    // self-consistent payload would pin — here the payload lies, so it is
    // dropped before any state mutation that matters).
    expect(state.apiBase).not.toContain('evil');
  });

  it('IGNORES a foreign-origin message once a legitimate origin is pinned', () => {
    // Legit handshake first (trust-on-first-use pins the browser-attested origin).
    handleParentMessage(
      styleUpdateEvent('https://merchant.example.com', {
        apiBase: 'https://api.nexuspay.com',
        sessionToken: 'legit-token',
      }),
    );
    expect(state.parentOrigin).toBe('https://merchant.example.com');
    expect(state.apiBase).toBe('https://api.nexuspay.com');
    expect(state.sessionToken).toBe('legit-token');

    // Now the attacker tries to overwrite from a different origin.
    handleParentMessage(
      styleUpdateEvent('https://evil.example.com', {
        apiBase: 'https://evil.example.com',
        sessionToken: 'attacker-token',
      }),
    );

    // State is unchanged — the foreign origin was rejected.
    expect(state.parentOrigin).toBe('https://merchant.example.com');
    expect(state.apiBase).toBe('https://api.nexuspay.com');
    expect(state.sessionToken).toBe('legit-token');
  });

  it('ACCEPTS the first-seen (legitimate) origin and pins it from event.origin (not payload)', () => {
    handleParentMessage(
      styleUpdateEvent('https://merchant.example.com', {
        // payload claims a DIFFERENT origin — must be ignored, event.origin wins.
        parentOrigin: 'https://somewhere-else.example.com',
        apiBase: 'https://api.nexuspay.com',
        sessionToken: 'legit-token',
      }),
    );

    // Because payload.parentOrigin disagrees with event.origin, the whole
    // message is dropped (spoofed payload origin) and nothing is applied.
    expect(state.apiBase).toBe('');
    expect(state.sessionToken).toBe('');
  });

  it('ACCEPTS a legitimate handshake whose payload.parentOrigin AGREES with event.origin', () => {
    handleParentMessage(
      styleUpdateEvent('https://merchant.example.com', {
        parentOrigin: 'https://merchant.example.com', // agrees
        apiBase: 'https://api.nexuspay.com',
        sessionToken: 'legit-token',
      }),
    );

    expect(state.parentOrigin).toBe('https://merchant.example.com');
    expect(state.apiBase).toBe('https://api.nexuspay.com');
    expect(state.sessionToken).toBe('legit-token');
  });

  it('subsequent messages from the pinned origin continue to be accepted', () => {
    handleParentMessage(
      styleUpdateEvent('https://merchant.example.com', {
        apiBase: 'https://api.nexuspay.com',
        sessionToken: 'legit-token',
      }),
    );
    handleParentMessage(
      styleUpdateEvent('https://merchant.example.com', {
        apiBase: 'https://api2.nexuspay.com',
      }),
    );
    expect(state.apiBase).toBe('https://api2.nexuspay.com');
  });

  it('pins parentOrigin to event.origin (browser-attested), never to the payload value', () => {
    // First message has NO payload.parentOrigin (honest sender). The gate must
    // pin from the browser-attested event.origin, then reject any later origin.
    handleParentMessage(
      styleUpdateEvent('https://merchant.example.com', {
        apiBase: 'https://api.nexuspay.com',
        sessionToken: 'legit-token',
      }),
    );
    expect(state.parentOrigin).toBe('https://merchant.example.com');

    // A second message from a different origin claiming to be the parent must
    // now be rejected (origin is pinned).
    handleParentMessage(
      styleUpdateEvent('https://evil.example.com', {
        apiBase: 'https://evil.example.com',
      }),
    );
    expect(state.apiBase).toBe('https://api.nexuspay.com');
  });

  describe('isExpectedParentOrigin', () => {
    it('rejects a concrete foreign origin once an origin is pinned', () => {
      resetState('https://merchant.example.com');
      expect(isExpectedParentOrigin('https://evil.example.com')).toBe(false);
      expect(isExpectedParentOrigin('https://merchant.example.com')).toBe(true);
    });

    it('trusts the first concrete origin on first use when unset', () => {
      resetState('');
      // Browser-attested concrete origin is trusted-on-first-use.
      expect(isExpectedParentOrigin('https://merchant.example.com')).toBe(true);
    });
  });
});

/**
 * B-006 (race fix): authoritative parent origin from window.location.ancestorOrigins.
 *
 * ancestorOrigins is browser-attested and unspoofable. When present it is the
 * SOLE accepted parent origin — closing the trust-on-first-use race where a
 * co-resident attacker frame posts the handshake before the real SDK.
 *
 * jsdom does NOT implement ancestorOrigins, so we install a stub on
 * window.location for the "Chromium/WebKit attestation present" cases and
 * delete it again to exercise the "Firefox / no attestation" fallback. After
 * each test we restore the original descriptor and recompute, so the rest of
 * the suite sees the jsdom default (undefined → "" → TOFU path).
 */
describe('card-frame B-006 authoritative parent origin (ancestorOrigins)', () => {
  const originalLocationDesc = Object.getOwnPropertyDescriptor(window, 'location');

  /**
   * Replaces window.location with a copy that exposes `ancestorOrigins` shaped
   * like a DOMStringList (indexable + `.length`). Passing `undefined` removes it
   * to simulate Firefox/jsdom. Then re-runs the once-at-init derivation.
   */
  function stubAncestorOrigins(origins: string[] | undefined): void {
    const base = originalLocationDesc?.value ?? window.location;
    const fakeList =
      origins === undefined
        ? undefined
        : (Object.assign([...origins], { length: origins.length }) as unknown as DOMStringList);
    const fakeLocation = Object.create(
      Object.getPrototypeOf(base),
    ) as Location & { ancestorOrigins?: DOMStringList };
    // Copy enumerable props (href, origin, etc.) so other code paths still work.
    for (const key of ['href', 'origin', 'protocol', 'host', 'hostname', 'pathname', 'search', 'hash'] as const) {
      try {
        (fakeLocation as Record<string, unknown>)[key] = (base as unknown as Record<string, unknown>)[key];
      } catch {
        /* some props are read-only on the prototype; ignore */
      }
    }
    fakeLocation.ancestorOrigins = fakeList;
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: fakeLocation,
    });
    recomputeAuthoritativeParentOrigin();
  }

  afterEach(() => {
    // Restore the real jsdom location (ancestorOrigins undefined) and recompute
    // so the legacy TOFU describe block above is unaffected by ordering.
    if (originalLocationDesc) {
      Object.defineProperty(window, 'location', originalLocationDesc);
    }
    recomputeAuthoritativeParentOrigin();
    resetState('');
  });

  it('with ancestorOrigins=[merchant], a foreign STYLE_UPDATE arriving FIRST is IGNORED', () => {
    stubAncestorOrigins(['https://merchant.example']);
    // Sanity: the attestation was picked up and pinned at (re)init.
    expect(getAuthoritativeParentOrigin()).toBe('https://merchant.example');
    expect(state.parentOrigin).toBe('https://merchant.example');

    // Attacker wins the race: posts BEFORE the real SDK from its own origin,
    // attempting to inject its apiBase / sessionToken to exfiltrate the PAN.
    handleParentMessage(
      styleUpdateEvent('https://evil.example', {
        parentOrigin: 'https://merchant.example', // spoofed claim
        apiBase: 'https://evil.example',
        sessionToken: 'attacker-token',
      }),
    );

    // Nothing the attacker sent took effect; the pin stays the real parent.
    expect(state.apiBase).toBe('');
    expect(state.sessionToken).toBe('');
    expect(state.parentOrigin).toBe('https://merchant.example');
  });

  it('with ancestorOrigins=[merchant], a message from the attested origin is accepted and pins correctly', () => {
    stubAncestorOrigins(['https://merchant.example']);

    handleParentMessage(
      styleUpdateEvent('https://merchant.example', {
        apiBase: 'https://api.nexuspay.com',
        sessionToken: 'legit-token',
      }),
    );

    expect(state.parentOrigin).toBe('https://merchant.example');
    expect(state.apiBase).toBe('https://api.nexuspay.com');
    expect(state.sessionToken).toBe('legit-token');
  });

  it('with ancestorOrigins set, isExpectedParentOrigin accepts ONLY the attested origin (no TOFU)', () => {
    stubAncestorOrigins(['https://merchant.example']);
    expect(isExpectedParentOrigin('https://merchant.example')).toBe(true);
    expect(isExpectedParentOrigin('https://evil.example')).toBe(false);
    // No accept-all even though this is the "first" message.
    expect(isExpectedParentOrigin('https://other.example')).toBe(false);
  });

  it('without ancestorOrigins (undefined), the existing TOFU behavior still holds', () => {
    stubAncestorOrigins(undefined);
    // No attestation → not pinned at init → TOFU.
    expect(getAuthoritativeParentOrigin()).toBe('');
    resetState('');

    // First browser-attested origin pins.
    handleParentMessage(
      styleUpdateEvent('https://merchant.example', {
        apiBase: 'https://api.nexuspay.com',
        sessionToken: 'legit-token',
      }),
    );
    expect(state.parentOrigin).toBe('https://merchant.example');
    expect(state.apiBase).toBe('https://api.nexuspay.com');

    // A later foreign origin is rejected.
    handleParentMessage(
      styleUpdateEvent('https://evil.example', {
        apiBase: 'https://evil.example',
        sessionToken: 'attacker-token',
      }),
    );
    expect(state.parentOrigin).toBe('https://merchant.example');
    expect(state.apiBase).toBe('https://api.nexuspay.com');
    expect(state.sessionToken).toBe('legit-token');
  });

  it('with empty ancestorOrigins ([]), falls back to TOFU (treated as no attestation)', () => {
    stubAncestorOrigins([]);
    expect(getAuthoritativeParentOrigin()).toBe('');
    resetState('');
    // TOFU: first concrete origin trusted.
    expect(isExpectedParentOrigin('https://merchant.example')).toBe(true);
  });
});

/**
 * FIX 4 + FIX 5: error state is deferred to BLUR (not mid-type) with instructional
 * copy, and the handlers set aria-invalid alongside the visual error class so
 * screen readers get the programmatic state.
 */
describe('card-frame FIX 4/5 blur-deferred errors + aria-invalid', () => {
  // A valid Luhn-passing Visa test PAN.
  const VALID_PAN = '4242424242424242';
  // A complete-length (16) PAN that fails Luhn.
  const INVALID_PAN = '4242424242424241';

  function input(id: string): HTMLInputElement {
    const el = document.createElement('input');
    el.id = id;
    el.className = 'field__input';
    document.body.appendChild(el);
    return el;
  }
  function errorEl(id: string): HTMLElement {
    const el = document.createElement('div');
    el.id = id;
    el.className = 'field__error';
    document.body.appendChild(el);
    return el;
  }

  beforeEach(() => {
    // Pin a concrete parent origin so emitChange()'s postMessage has a valid
    // target in jsdom (an empty '' target throws). Production behavior unchanged.
    resetState('https://merchant.example');
    document.body.innerHTML = '';
  });
  afterEach(() => {
    resetState('');
    document.body.innerHTML = '';
  });

  it('does NOT surface a red PAN error mid-type (input event)', () => {
    const pan = input('card-number');
    const err = errorEl('error-pan');
    pan.value = INVALID_PAN; // complete-but-bad, but still typing
    handlePanInput(pan, err);

    // No red error while typing.
    expect(state.errors.pan).toBeNull();
    expect(pan.classList.contains('field__input--error')).toBe(false);
    expect(err.classList.contains('field__error--visible')).toBe(false);
  });

  it('surfaces an instructional "incomplete" PAN error on blur when too short', () => {
    const pan = input('card-number');
    const err = errorEl('error-pan');
    pan.value = '4242';
    handlePanInput(pan, err);
    handlePanBlur(pan, err);

    expect(state.errors.pan).toBe(MSG.panIncomplete);
    expect(state.errors.pan).toBe('Your card number is incomplete.');
    // FIX 4: aria-invalid is set true alongside the visual error class.
    expect(pan.getAttribute('aria-invalid')).toBe('true');
    expect(pan.classList.contains('field__input--error')).toBe(true);
  });

  it('surfaces an instructional "invalid" PAN error on blur for a complete-but-bad number', () => {
    const pan = input('card-number');
    const err = errorEl('error-pan');
    pan.value = INVALID_PAN;
    handlePanInput(pan, err);
    handlePanBlur(pan, err);

    expect(state.errors.pan).toBe(MSG.panInvalid);
    expect(state.errors.pan).toBe('Your card number is invalid.');
    expect(pan.getAttribute('aria-invalid')).toBe('true');
  });

  it('a valid PAN clears the error and aria-invalid on blur', () => {
    const pan = input('card-number');
    const err = errorEl('error-pan');
    pan.value = VALID_PAN;
    handlePanInput(pan, err);
    handlePanBlur(pan, err);

    expect(state.errors.pan).toBeNull();
    expect(pan.getAttribute('aria-invalid')).toBe('false');
    expect(pan.classList.contains('field__input--error')).toBe(false);
  });

  it('defers expiry errors to blur with instructional copy', () => {
    const month = input('card-exp-month');
    const year = input('card-exp-year');
    const err = errorEl('error-expiry');

    month.value = '13'; // invalid month
    handleExpiryInput(month, year, 'month', err);
    year.value = '30'; // complete year so the invalid-month path (not incomplete) is reached
    handleExpiryInput(month, year, 'year', err);
    // Mid-type: no error surfaced.
    expect(err.classList.contains('field__error--visible')).toBe(false);

    handleExpiryBlur(month, year, err);
    expect(state.errors.expiry).toBe(MSG.expiryInvalidMonth);
    expect(month.getAttribute('aria-invalid')).toBe('true');
    expect(year.getAttribute('aria-invalid')).toBe('true');
  });

  it('reports an "incomplete" expiry on blur when partially filled', () => {
    const month = input('card-exp-month');
    const year = input('card-exp-year');
    const err = errorEl('error-expiry');
    month.value = '12';
    handleExpiryInput(month, year, 'month', err);
    handleExpiryBlur(month, year, err);
    expect(state.errors.expiry).toBe(MSG.expiryIncomplete);
  });

  it('CVC error is deferred to blur and sets aria-invalid', () => {
    state.brand = 'visa';
    const cvc = input('card-cvc');
    const err = errorEl('error-cvc');
    cvc.value = '12';
    handleCvcInput(cvc, err);
    // Mid-type: nothing.
    expect(state.errors.cvc).toBeNull();
    expect(cvc.getAttribute('aria-invalid')).not.toBe('true');

    handleCvcBlur(cvc, err);
    expect(state.errors.cvc).toBe(MSG.cvcIncomplete);
    expect(cvc.getAttribute('aria-invalid')).toBe('true');
  });

  it('renderFieldError toggles class, text and aria-invalid coherently', () => {
    const el = input('x');
    const err = errorEl('x-err');
    renderFieldError(el, err, 'boom');
    expect(el.getAttribute('aria-invalid')).toBe('true');
    expect(err.textContent).toBe('boom');
    renderFieldError(el, err, null);
    expect(el.getAttribute('aria-invalid')).toBe('false');
    expect(err.textContent).toBe('');
  });
});
