# Premium checkout widget — design spec

> Source: deep web-research workflow (Stripe Payment Element, Adyen, Checkout.com, Square, Braintree, Mollie, Paddle, Razorpay) synthesized into a buildable spec for the NexusPay checkout-sdk. Goal: match or surpass the best.


## Design principles

Eight principles, each tied to a provider exemplar and to what reads premium vs cheap. They are the rubric every later section serves.

1. CRAFT IS THE #1 TRUST SIGNAL — pixel rhythm over badges. Baymard: perceived security is a "gut feeling" set by how secure the page LOOKS; a single misaligned field or reflow undoes every badge. So a strict 4px grid, 1px hairlines, zero layout shift, and perfect optical alignment ARE the conversion feature. (Baymard perceived-security; Linear/Vercel craft.)

2. ONE ACCENT, DERIVED NEUTRALS — restrained palette. A single brand color used ONLY on the pay button, focus ring, and selected tab; everything else is a near-black ink + 2 neutrals. No rainbow, no gradients on the CTA. (Razorpay derive-from-one-primary; Stripe defaults text #30313d / one primary #0570de.)

3. DEPTH VIA HAIRLINES + SOFT LAYERED SHADOW, never a single hard drop shadow or neon glow. Two/three stacked low-alpha shadows tinted navy (16,24,40) not black; inputs flat-with-hairline at rest, depth only on focus. (Stripe's layered 0 1px 1px rgba(0,0,0,.03), 0 3px 6px rgba(18,42,66,.02); Linear elevation.)

4. THE FOCUS RING IS THE PREMIUM/CHEAP DIVIDER — a designed box-shadow halo (1px solid inner + 3px translucent outer), NEVER the browser default outline, and :focus-visible so it shows for keyboard not mouse. (Stripe focusBoxShadow 0 0 0 3px rgba(5,112,222,.1) + focusOutline:none; WCAG 2.4.13.)

5. VISUALLY ENCAPSULATE THE CARD FIELDS so the sensitive section looks "more robust than the rest" — distinct surface tint + larger radius + micro-shadow unique to that group. 66–89% of sites fail this; it's the under-used 15–30% conversion move. (Baymard encapsulation.)

6. MOTION IS A DESIGNED DEFAULT, not decoration — short (120–300ms), asymmetric ease-out, transform+opacity only (60fps), one spring reserved for the success checkmark, all gated by prefers-reduced-motion. If killing animation breaks the flow, the animation was superfluous. (Villar/Stripe; Material easing; Stripe's only knob is disableAnimations.)

7. REAL, UNALTERED CARD-BRAND SVGs on hairline chips — full-color when detected, muted/mono in the accepted row; the cheap tell is a grey glyph or recolored/stretched mark. NexusPay's current Discover/JCB/UnionPay marks use <text> placeholders — replace with real vector marks. (Solidgate logo guidance; aaronfagan/svg-credit-card-payment-icons, 780×500 ISO ratio.)

8. THE BOUNDARY IS THE PRODUCT — the card fields live in a cross-origin PCI iframe, so premium styling must be IDENTICAL on both sides of the seam. Chrome (border/ring/error) lives on the parent wrapper toggled by classes; only text-level props travel into the iframe via tokens. NexusPay uniquely controls its own card-frame.html, so it CAN inject the real brand @font-face inside the iframe — closing the typography gap that forces Adyen/Square to system-fonts-only. (Stripe Appearance; Checkout.com hide-border-style-container; the differentiator.)

CHEAP, explicitly banned: heavy/multi-hue gradients, neon glows, the UA focus outline (or outline:none with no replacement), ad-hoc spacing (13px here/15px there), 2px field borders, pure #000/#FFF dark mode with black shadows, generic spinners as the primary loader, 7+ trust badges, <16px input text (iOS zoom), linear easing on movement, animating width/height/top/left.

## Premium-vs-cheap recipe

PREMIUM (build against this): consistent 4px grid (12px pad ≤ 16px gap ≤ 24px section); 1.25 type scale topping at 16px input / 13px helper / 28px total; 16px+ input text; 1px low-contrast hairlines on integer boundaries; 2–3-stop soft NAVY-tinted shadows with negative spread; two-layer :focus-visible ring (1px solid primary + 3px translucent halo ≥3:1, NEVER the UA outline); the encapsulated card group with its own surface+radius+micro-shadow; tabular-nums + slashed-zero on all amounts and the PAN; real full-color network SVGs on white hairline chips; one accent color (button+focus+selected only); near-black blue-tinted ink #1A1A2E not #000; antialiased smoothing; intentional cubic-bezier easings at 150/200/300ms + one spring for the success check; brand crossfade not hard-swap; blur-based humane error copy that clears on edit with reserved height; dark mode via lighter surfaces + 1px highlight (not shadows) and lighter-but-not-neon danger/success; prefers-reduced-motion + forced-colors honored; iframe seam invisible (identical radius/border/padding/font-metrics/ring on both sides).

CHEAP (banned): UA focus outline or outline:none with no replacement; the rgba(hex,alpha) focus-ring bug (renders nothing); pure-black or single hard drop shadows; neon glows / multi-hue background gradients; recreated/recolored/stretched or <text>-based brand logos; ad-hoc spacing; 2px field borders; <16px inputs (iOS zoom); a generic spinner as the primary loader; 700+ weights in checkout; bare "Continue" CTA; 7+ trust badges or a self-minted seal as the lead trust signal; janky layout shift when an error appears; linear easing on movement; animating width/height/top/left; pure #000/#FFF + neon in dark mode.

THE ONE-LINE TEST: if it isn't on the 4px grid, doesn't consume a token, or shifts layout — it isn't premium yet.

## Token system

Extend the 11-variable system in tokens.ts to a Stripe-class set. Two layers: (A) hex/length VARIABLES merchants set (must be solid hex for contrast auto-derivation, mirroring Stripe's constraint); (B) derived/internal tokens emitted by generateCSSVariables(). Keep current defaults — #0066FF/#1A1A2E/8px radius/4px unit are MORE modern than Stripe's 4px/2px.

NEW MERCHANT-FACING VARIABLES (add to AppearanceVariables in types.ts + DEFAULT_VARIABLES + VARIABLE_MAP):
  colorTextSecondary  '#6B7280'  → --nxp-color-text-secondary  (helper/secure-line/powered-by/placeholder)
  colorTextPlaceholder '#9CA3AF' → --nxp-color-text-placeholder (promote the hard-coded value at base-styles.ts:39 + card-frame.css:76)
  colorBorder         '#E5E7EB'  → --nxp-color-border  (promote INPUT_STATES.borderDefault to a real, theme-overridable token)
  colorBorderHover    '#D1D5DB'  → --nxp-color-border-hover
  colorWarning        '#D97706'  → --nxp-color-warning
  colorSurface        '#FFFFFF'  → --nxp-color-surface (the card surface, can differ from page colorBackground — enables the floating card)
  onPrimary           '#FFFFFF'  → --nxp-on-primary (button label color; see contrast note in accessibility)
  fontWeightMedium    500        → --nxp-font-weight-medium (currently only normal/bold — labels/buttons want the mid weight)
  buttonBorderRadius  '8px'      → --nxp-button-border-radius (falls back to borderRadius; lets the pay button be rounder independently)

COLOR RAMP (light): primary #0066FF (action), text #1A1A2E (ink, blue-tinted near-black — keep), text-secondary #6B7280, placeholder #9CA3AF, border #E5E7EB, border-hover #D1D5DB, surface #FFFFFF on page #F6F8FA, danger #DC2626, success #16A34A, warning #D97706.

SPACING SCALE (emit as derived, off the 4px unit so no raw px sneaks in):
  --nxp-space-1 calc(unit*1)=4px · -2 8px · -3 12px · -4 16px · -5 20px · -6 24px · -8 32px.
  --nxp-grid-row-spacing 16px (bump field vertical gap from 12px) · --nxp-grid-column-spacing 12px (keep expiry/cvc column gap).
  Rhythm: input internal padding 12px ≤ inter-field gap 16px ≤ section gap 24px. Card inner pad 24px desktop / 16px mobile.

TYPE SCALE (emit as derived rem off fontSizeBase 16px; 1.25 modular):
  --nxp-font-size-xs .75rem(12px legal) · -sm .8125rem(13px helper/error) · -label .875rem(14px) · -base 1rem(16px input/body) · -lg 1.125rem(18px section heading) · -xl 1.5rem(24px) · -2xl 1.75rem(28px total).
  Weights 400 body / 500 labels & secondary / 600 emphasis & total — never 700+ (the current success/failure title at 700 in checkout.css:185 reads loud → drop to 600).
  --nxp-font-line-height 1.5 (1.2 for the amount). Family: keep the tuned system stack as default.

RADII: --nxp-border-radius 8px (inputs/buttons) · --nxp-radius-group 12px (card encapsulation container, one step larger) · --nxp-radius-chip 4px (brand chips).

BORDER/HAIRLINE: 1px solid --nxp-color-border at rest; never 2px on fields.

SHADOW/ELEVATION (the single biggest current gap — zero shadow tokens today; tint navy not black):
  --nxp-shadow-sm  0 1px 2px rgba(16,24,40,.05)
  --nxp-shadow-card 0 1px 2px rgba(16,24,40,.04), 0 1px 3px rgba(16,24,40,.06)   (the encapsulated card group)
  --nxp-shadow-md  0 1px 3px rgba(16,24,40,.07), 0 1px 2px rgba(16,24,40,.06)
  --nxp-shadow-lg  0 4px 6px -2px rgba(16,24,40,.04), 0 12px 16px -4px rgba(16,24,40,.08)  (floating page card / modal; note negative spread)

FOCUS RING: --nxp-focus-ring-width 3px · --nxp-focus-ring-alpha 0.15 (light; 0.25 night) · --nxp-focus-outline-offset 2px.

MOTION TOKENS (add easing tokens so motion is themeable, not literal — this is what keeps the two sides of the iframe in lockstep):
  --nxp-ease-out cubic-bezier(0.16,1,0.3,1)   (entrances: focus ring, label float, brand crossfade, error slide-in)
  --nxp-ease-standard cubic-bezier(0.4,0,0.2,1) (hover/tab/border color)
  --nxp-ease-in cubic-bezier(0.4,0,1,1)        (exits)
  --nxp-ease-spring cubic-bezier(0.34,1.56,0.64,1) (success checkmark ONLY)
  --nxp-dur-instant 100ms · -fast 150ms · -base 200ms · -slow 300ms.
  Reframe the existing ANIMATION map to reference these and upgrade EASING in tokens.ts: brandCrossfade ease-out→--nxp-ease-out; errorSlideIn ease-out→--nxp-ease-out; tabSwitch ease-in-out→--nxp-ease-standard; successCheckmark linear→--nxp-ease-spring (stroke-draw, not a linear pop).

NIGHT parity set (expand NIGHT_VARIABLES from 3 → full): background #0F172A, surface #1E293B (elevation by lightness, one step up), text #E2E8F0, text-secondary #94A3B8, placeholder #64748B, border #334155, border-hover #475569, primary #3B82F6, danger #F87171 (lighter to hold 4.5:1, NOT neon), success #4ADE80, on-primary #FFFFFF, card-group-bg #111A2E.

## Layout

DESKTOP (≥640px) — "floating single card on a tinted page," the Apple-Pay-sheet / Stripe-hosted signature.
- Page: background --nxp-color-background #F6F8FA (tint, NOT pure white — currently #F9FAFB in checkout.css:19, fine). Content centered.
- Keep the existing two-column .checkout__layout (form left, summary right) but tighten: grid-template-columns minmax(0,1fr) 360px; gap 40px (down from 48); max-width 920px.
- The FORM column is wrapped in a floating card: surface #FFFFFF, --nxp-radius-group 12px, --nxp-shadow-lg, inner padding 32px. Cap form content width 440–480px.
- BrandingHeader sits at the top inside or above the card: merchant logo (28px) + name at 18px/600, 1px hairline divider below (keep .branding-header, swap border to --nxp-color-border).
- Vertical rhythm inside the form: section heading 18px/600 → 16px gap → fields → 24px section gap → 32px above the pay button.
- ORDER SUMMARY (right, sticky top:24px): its own card — surface #FFFFFF, 1px hairline, --nxp-radius-group 12px, --nxp-shadow-card, pad 24px. Line items 14px, total row 28px/600 right-aligned with tabular-nums.

MOBILE (<640px) — single column.
- Summary collapses to the top (order:-1, already done) as a compact "Pay $X · view details" disclosure row, expandable. Form card goes edge-to-edge minus 16px page padding, radius 12px, no heavy shadow (use --nxp-shadow-card only).
- Card inner padding 16px.

PAYMENT PANEL STRUCTURE (the form column, top→bottom):
1. Method switcher (Tabs / accordion) — card | Apple Pay | Google Pay. Selected tab gets the box-shadow ring treatment (see components).
2. The ENCAPSULATED CARD GROUP (new): a bordered container (surface --nxp-color-surface, 1px --nxp-color-border, --nxp-radius-group 12px, --nxp-shadow-card) with a header row: a 16px lock glyph + "Card details" (14px/600) on the left, "Encrypted & secure" (13px --nxp-color-text-secondary) on the right. Inside it sits the PCI iframe (card number full-width → expiry|cvc 50/50). This is the highest-ROI trust move and is unique to this group only.
3. Billing address (AddressElement) — labels above, single column; postal/ZIP only when AVS needed.
4. Pay button (full width).
5. Security line + Powered-by.

EXACT FIELD ORDER inside the iframe (already correct in card-frame.html): Card number (grid-column 1/-1) → Expiry (col 1) + CVC (col 2). Labels ABOVE inputs (never placeholder-as-label). Input height 44–48px (12px pad + 16px text + 1.5 lh clears 44px).

GRID: 4px base everywhere; field gap 16px (raise card-form gap from spacing*3=12 to spacing*4=16, keeping expiry/cvc column gap at 12px so the pair reads as one unit).

## Components

METHOD TABS (.Tab in base-styles.ts) — keep the inline-flex row on desktop, the stacked radio-cards on mobile (already at base-styles.ts:254). Premium upgrade: a SELECTED tab gets Stripe's tab ring — box-shadow: 0 1px 1px rgba(0,0,0,.03), 0 3px 6px rgba(18,42,66,.02), 0 0 0 2px var(--nxp-color-primary); selected weight 600, brand-tinted bg via color-mix (NOT the broken rgba(hex,.06) at base-styles.ts:122/128/270). .Tab__icon 24×16 brand/wallet marks.

CARD-NUMBER FIELD (inside iframe) — 44–48px tall, 1px --nxp-color-border, --nxp-border-radius 8px, 12px pad, font-variant-numeric: tabular-nums (digits sit on a fixed grid so the brand icon doesn't appear to shift). Live brand SVG right-aligned at 12px inset, box 32×20 on a 4px-radius white chip with a 1px hairline (background:#fff; border:1px solid rgba(0,0,0,.06); border-radius:4px; padding:2px) so colored marks read cleanly on any bg incl. dark. At rest: generic card placeholder; on IIN detection: crossfade (opacity 1→0→1 + scale .9→1) 150ms ease-out to the single detected brand. Format mask 4-4-4-4, Amex 4-6-5 (already implemented in card-frame.html formatPAN).

EXPIRY + CVC — 50/50 row (grid-column 1 / 2). Expiry is MM / YY with a center "/" separator (keep .expiry-row); auto-advance month→year (already wired). CVC grows to 4 digits + placeholder "1234" on Amex (already wired). CVC field gets a small help-glyph tooltip ("3 digits on the back, 4 on front for Amex").

BILLING (AddressElement) — labels above, country select first, then line1, city, state, postal. Single column. Inputs identical to card fields (same .Input chrome) so the seam between iframe and same-origin fields is invisible.

PAY BUTTON (.Button / .checkout__submit) — full width, min-height 48px desktop / 52px mobile, --nxp-button-border-radius, label "Pay {amount}" with a leading 14px lock glyph (already present). Flat brand fill + a very subtle same-hue vertical gradient (top ~4% lighter) + inset 0 1px 0 rgba(255,255,255,.15) top highlight for tactility — NOT a multi-hue gradient. Replace filter:brightness(.9) hover (base-styles.ts:164, checkout.css:72) with an explicit -8% lightness primary token shift + lift translateY(-1px) + shadow sm→md; :active scale(.98) over 100ms. Amount uses tabular-nums.

ORDER SUMMARY (.payment-summary) — title 15px/600, hairline divider, rows 14px (label --nxp-color-text-secondary / value text), total row 28px/600 right-aligned, tabular-nums + slashed-zero so the total never reflows.

TRUST FOOTER — one centered security line (13px --nxp-color-text-secondary, 12px lock glyph) "Your data is encrypted and secure", and below it a muted "Powered by NexusPay" (12px, secondary). Above the card group, an accepted-card row of muted (opacity .4 / mono) brand marks at 36×24. Cap at these 3 signal types — no more (7+ badges hurts conversion -8%).

## States

Five states, each with exact visual treatment. Card states render INSIDE the iframe (card-frame.css/html); same-origin field/button states render from base-styles.ts/checkout.css — both must look identical.

1. IDLE — input: surface #FFFFFF (#1E293B night), 1px --nxp-color-border hairline, no shadow (flat-with-hairline), placeholder --nxp-color-text-placeholder. Hover: border → --nxp-color-border-hover only (color change, not width), 150ms --nxp-ease-standard. Brand icon shows generic card mark. Button: flat brand fill, --nxp-shadow-sm.

2. VALIDATING (live, pre-blur) — compute validity as the user types (Luhn/IIN drive the brand icon and an optional success tick) but DO NOT surface red errors mid-keystroke (Stripe rule; card-frame.html already only sets pan error at ≥13 digits — extend to defer the red border to blur). On a fully valid card number, fade in a 16px --nxp-color-success check (only on the number field, where detection already lives — per-field green everywhere over-clutters). Focus ring present (state 4 of the input).

3. ERROR (inline, on blur) — border → --nxp-color-danger; focus ring swaps to danger color same geometry (0 0 0 3px color-mix danger 15%). Error text appears BELOW the field, fading+sliding down (translateY(-4px)→0, opacity 0→1) 200ms --nxp-ease-out (keep .field__error slide-in). RESERVE the error slot height (min-height on the error row or absolute-position) so the form never jumps. On a HARD failure (declined / invalid number on blur/submit) the field does ONE horizontal shake: keyframes translateX 0,-6px,6px,-4px,4px,0 over 320ms ease-in-out, never looped, never per-keystroke (Stripe/Villar). Error COPY is instructional, not a verdict: "Your card number looks incomplete", "Check the expiry date — use MM / YY", never "Invalid input". Clear-on-edit: the instant the user re-focuses/edits, fade the error out over 120ms and drop the danger border. Error must carry an icon + text (not color-alone) for a11y.

4. PROCESSING — in-place button morph (do NOT collapse width): label fades out (opacity 300ms), a centered 18–20px / 2px-stroke spinner fades in, button → disabled + cursor:wait + subtle desaturate; button height fixed (min-height 48px) so no reflow. Spinner rotates 360deg / 600–800ms LINEAR (linear is correct for a constant-velocity spinner). Gate the spinner behind a ~200ms delay so sub-second confirmations skip it (a 200ms flash reads as jank). Disable on first click to prevent double-submit. Optional stable copy swap to "Processing…" (no animated dots).

5. SUCCESS — full-screen SuccessConfirmation. Wrapper pop-in scale 0→1.1→1 over ~500ms --nxp-ease-spring. Circle draws stroke-dashoffset 166→0, 600ms --nxp-ease-spring @200ms; check draws stroke-dashoffset 48→0, 400ms --nxp-ease-spring @700ms (staggered so it feels hand-drawn — the existing 300ms stagger is close; widen to 700ms and swap linear→spring). Color --nxp-color-success; optional soft success-ring pulse (scale 1→1.4, opacity .3→0, 600ms ease-out, once) behind it. Amount/order text fades up underneath. prefers-reduced-motion: show the final checkmark statically with a 150ms opacity fade only.

## Motion

Every micro-interaction, all reduced-motion aware (the parent currently lacks a reduced-motion block in base-styles.ts — ADD one mirroring card-frame.css:199; checkout.css:347 already has one).

FOCUS RING IN/OUT — 150ms --nxp-ease-out on border-color + box-shadow. Never instant, never UA outline. transition: border-color var(--nxp-dur-fast) var(--nxp-ease-out), box-shadow var(--nxp-dur-fast) var(--nxp-ease-out).

FLOATING LABEL (if adopted as v2 'floating' mode) — resting 16px placeholder-sized → on focus/filled translateY(-130%) scale(.8) over 200ms --nxp-ease-out (use transform+scale, GPU, NOT font-size which triggers layout); color → primary on --focused. Never float on hover. Default stays labels-above.

BRAND CROSSFADE — 150ms --nxp-ease-out, opacity 1→0→1 + scale .9→1 on the incoming SVG; never a hard swap. (Existing token brandCrossfade:150 — keep, upgrade easing.)

ERROR REVEAL — 200ms --nxp-ease-out, translateY(-4px)→0 + opacity 0→1; danger border lands simultaneously. Clear-on-edit fade-out 120ms. Hard-failure shake 320ms ease-in-out once.

TAB SWITCH — 200ms --nxp-ease-standard on color/border/background; selected ring composites in.

BUTTON — hover: bg -8% lightness + translateY(-1px) + shadow sm→md, 150ms --nxp-ease-standard; active: translateY(0) scale(.98) 100ms; never 300ms+, never bounce a CTA. Focus-visible: same 3px ring as inputs.

PROCESSING SPINNER — 600–800ms linear infinite; gated behind 200ms delay.

SUCCESS CHECKMARK — wrapper pop ~500ms --nxp-ease-spring; circle draw 600ms @200ms; check draw 400ms @700ms; both --nxp-ease-spring. ~1.1s total choreography. The one place spring/overshoot is allowed.

SKELETON SHIMMER — 1500ms linear infinite, background-size 200% 100%, position 200%→-200% (keep). Low-contrast sweep (highlight only ~8–12% lighter than base — currently #F3F4F6 on #E5E7EB, fine). Skeletons must match real field geometry (same heights/radii/gaps) and crossfade to content over ~200ms. Reduced-motion: static or 1s opacity-pulse.

GLOBAL RULE: only transform + opacity animate (60fps); never width/height/top/left. All durations/easings consume tokens, never literals — this is what keeps the iframe and parent identical and lets the Appearance API stay consistent (Stripe gates motion with one disableAnimations boolean).

## Trust signals

Cap at 3 signal TYPES (1–3 converts +23% vs none; 7+ converts -8%; Baymard). In priority order, with exact placement:

1. THE ENCAPSULATED CARD GROUP (highest ROI, 15–30% lift for lesser-known brands) — wrap card number/expiry/cvc in a distinct container UNIQUE to that section: surface --nxp-color-surface (#FCFCFD light tint / #111A2E night against the page), 1px --nxp-color-border, --nxp-radius-group 12px (one step larger than inputs), --nxp-shadow-card. Header row: 16px lock glyph (--nxp-color-text-secondary, ~60% opacity) + "Card details" (14px/600) left, "Encrypted & secure" (13px secondary) right. The treatment must NOT be applied to other fields or the effect is lost. Chrome lives on the PARENT (around the iframe) so it can use full CSS.

2. REAL CARD-BRAND SVGs + in-field detection crossfade — accepted-card row above the card field at 36×24, muted (opacity .4 or mono) for non-detected brands; on detection, full-color single mark crossfades into the field right edge (150ms). FUNCTIONAL FIX: replace the <text>-based Discover/JCB/UnionPay/Maestro placeholders in elements/icons/index.ts with real vector marks (aaronfagan/svg-credit-card-payment-icons, Apache-2.0, flat full-color style) — recreated/text logos read scammy. Keep Visa ≥5mm digital height; never recolor/stretch Mastercard circles or Amex.

3. ONE SECURITY LINE + LOCK near the CTA — 13px --nxp-color-text-secondary, centered, leading 12px lock glyph: "Your data is encrypted and secure." Plus a quiet "Powered by NexusPay" (12px, secondary) below. Rename the current "Secured by NexusPay" (CheckoutPage.tsx:123) to the Stripe-style encryption copy — a homemade seal only works once the brand is known; until then lean on recognized card-network marks + the lock affordance, not a self-minted badge. Also fix .checkout__secure-text using opacity:.5 on --nxp-color-text (checkout.css:99) → use the real --nxp-color-text-secondary token so it stays AA-legible.

ANTI-PATTERNS (banned): grid of 5+ seals, animated "100% SECURE" ribbons, neon padlock glows, pixelated/mismatched badge PNGs, un-clickable SSL-cert seals, security copy in red or all-caps. Restraint IS the premium signal. STOP at these three.

## SVG / CSS craft

CARD-BRAND SVG APPROACH — real vector marks only, on hairline chips. Replace the <text>-based placeholders (ICON_DISCOVER, ICON_JCB, ICON_UNIONPAY, ICON_MAESTRO in elements/icons/index.ts use <text> + system-ui — they render scammy/inconsistent) with authentic flat-style marks from aaronfagan/svg-credit-card-payment-icons (Apache-2.0; viewBox 0 0 780 500 = ISO ID-1 1.56:1). Keep ICON_VISA/MASTERCARD/AMEX (real paths, good). Render each on a 4px-radius white chip with a 1px hairline (background:#fff; border:1px solid rgba(0,0,0,.06); border-radius:4px; padding:2px) so colored logos read on any input bg incl. dark mode. In-field box 32×20 (current, fine; or 32×21 to honor 1.56:1); accepted-row 36×24; tab/wallet 24×16. Must inline the SVG into card-frame.html (cross-origin can't lazy-load cleanly). Visa full-color ≥5mm digital; never recolor/squish Mastercard circles or Amex. Wallet marks (Apple/Google Pay) unmodified per brand guidelines.

ICONOGRAPHY — single 1.5px-stroke line style using currentColor (the lock glyph in CheckoutPage.tsx:109 is the right weight — standardize all UI glyphs to 1.5px stroke, currentColor, 16px box). Success checkmark is stroked paths (circle dasharray 166 + check dasharray 48 — already in checkout.css; the iframe/base-styles versions match), animated via stroke-dashoffset.

PREMIUM DEPTH/HAIRLINE/RENDERING CSS:
  - Hairlines: 1px solid border on integer pixel boundaries (sub-pixel borders blur on retina). Premium move: inset hairline crisp-up on the card group — box-shadow: inset 0 0 0 1px rgba(0,0,0,.04) light, or inset 0 1px 0 rgba(255,255,255,.06) top-highlight in dark.
  - Shadows: the layered navy-tinted tokens (--nxp-shadow-card/-md/-lg) with negative spread on -lg to keep the blur tight. Never pure-black, never a single hard drop shadow.
  - Numerals: font-variant-numeric: tabular-nums on the card-number input AND the order total + success amount, slashed-zero on the total — so digits sit on a stable grid (the brand icon won't appear to shift as digits fill) and the total never reflows/jitters. Verify the chosen font ships tnum or it silently no-ops.
  - Smoothing: keep -webkit-font-smoothing: antialiased + -moz-osx-font-smoothing: grayscale on both sides (already present) — correct 2026 cross-platform default, matters most for light-on-dark. text-rendering: optimizeLegibility ONLY on headings/the amount, never on inputs.

STYLING ACROSS THE PCI IFRAME BOUNDARY (the load-bearing technique):
  - Keep the architecture: parent injects --nxp-* via postMessage STYLE_UPDATE; the iframe applies them on document.documentElement (card-frame.html:481). Chrome (border/ring/error) renders INSIDE the iframe from those vars; same-origin fields render from base-styles.ts. Both must be visually identical — which is why easing/duration MUST be tokens not literals.
  - CRITICAL BUG TO FIX: base-styles.ts:47/55 use box-shadow: 0 0 0 3px rgba(var(--nxp-color-primary,#0066FF), var(--nxp-focus-ring-alpha,.15)) — rgba() cannot take a hex as its first arg, so the SAME-ORIGIN focus ring is BROKEN (renders nothing). The iframe (card-frame.css:85) correctly uses color-mix(in srgb, ... 15%, transparent). Standardize on color-mix EVERYWHERE; same rgba(hex) bug poisons the tab tints at base-styles.ts:122/128/270 — convert all to color-mix. Post-fix, the two sides finally match.
  - Extend the postMessage VARIABLE map (card-frame.html:473 + card-frame.ts) to carry the NEW tokens (text-secondary, color-border/-hover, surface, on-primary, font-weight-medium, focus-ring-alpha, shadow tokens, easing tokens) so the iframe consumes the same set.
  - Add a per-selector CSS-property ALLOWLIST in generateRuleOverrides() (today it passes merchant rules through unfiltered — a security gap): allow only cosmetic props (Stripe's .Input allowlist: backgroundColor, border*, borderRadius, boxShadow, color, font*, letterSpacing, lineHeight, margin/padding, outline*, textDecoration/Shadow/Transform, transition, -webkit-font-smoothing, -webkit-text-fill-color). Silently drop position/display/z-index/content so a malicious merchant rule can't reposition the PCI iframe inputs.
  - WEB FONTS: NexusPay controls card-frame.html, so accept a fonts:[{cssSrc}|{family,src,weight}] array in the appearance payload and @font-face it INSIDE the frame, then set --nxp-font-family — closing the cross-origin typography gap (Adyen/Square are stuck system-only). Default to the tuned system stack. Never let merchant vars push the card-field font below 16px (iOS zoom).
  - Add ResizeObserver → postMessage('resize', height) from the frame so the parent reserves no dead space when the error slide-in changes height.

## Dark mode & responsive

DARK MODE — a full parallel palette, not inverted greys. Expand NIGHT_VARIABLES (currently 3 tokens) to full parity and complete PRESET_NIGHT rules in presets.ts:
  background #0F172A · surface #1E293B (one step lighter = elevation WITHOUT shadow, since shadows barely read on dark) · text #E2E8F0 (never pure white — avoids halation) · text-secondary #94A3B8 · placeholder #64748B · border #334155 · border-hover #475569 · primary #3B82F6 (raise to #60A5FA only if it fails 3:1 on the button) · danger #F87171 (lighter to hold 4.5:1, NOT neon red) · success #4ADE80 (desaturated, not neon) · card-group-bg #111A2E.
  Depth via lighter surface + a 1px top-highlight border (border-top:1px solid rgba(255,255,255,.06)), NOT box-shadow.
  Focus-ring alpha UP to 0.25 in night (a 15% ring vanishes on dark) — set --nxp-focus-ring-alpha per preset.
  Brand SVG chips: keep the white chip behind colored marks so they read on the dark surface, OR switch to mono variant via a --nxp-brand-logo-variant token (Stripe's logoColor light|dark).
  Autofill: keep the -webkit-box-shadow inset → surface color + -webkit-text-fill-color override (already in card-frame.css:175 + presets.ts:30) — Chrome's yellow autofill on dark is the worst cheap tell.
  MODE DETECTION: don't rely on the iframe's own @media (prefers-color-scheme) alone — framed fields would drift from the parent. Adopt explicit colorScheme 'light'|'dark'|'auto'; on toggle the parent re-serializes tokens and re-postMessages STYLE_UPDATE to the iframe (the existing handshake at card-frame.html:464 already does this for variables — extend with a theme name). Set [data-theme] on the parent.

RESPONSIVE — single column + sticky pay bar:
  - <640px (BREAKPOINTS.mobile, correct): single column; summary collapses to a top disclosure row (keep order:-1). Card number full-width, expiry|cvc stay side-by-side (short fields, expected even on mobile — keep the 2-col grid). Nothing else multi-column.
  - STICKY PAY BAR (parent SPA, CheckoutPage.tsx — NOT the iframe): on mobile the pay CTA pins position:sticky bottom:0; full-width; min-height 52–56px; 16px horizontal padding; 1px hairline top border + soft upward shadow (0 -1px 0 var(--nxp-color-border), 0 -8px 24px rgba(0,0,0,.06)) so content scrolls under with depth; safe-area: padding-bottom: max(16px, env(safe-area-inset-bottom)). Label "Pay {amount}", never bare "Continue".
  - Input font-size ≥16px on mobile (already 1rem) to stop iOS focus-zoom — never let a theme drop the card field below 16px.
  - DESKTOP: cap card ~440–480px, centered, floating on the #F6F8FA tinted page with --nxp-shadow-lg.
  - Inputs already carry inputmode="numeric" + autocomplete cc-* (card-frame.html) — keep; ensures number pad + OS card autofill. Real-time 4-4-4-4 grouping present.

## Accessibility

WCAG 2.2 AA, with the cross-iframe wrinkle. Concrete values:

CONTRAST:
  - Body/label text ≥4.5:1; large text (≥24px, or ≥18.66px bold) ≥3:1.
  - LATENT BUG: the pay-button label #FFFFFF on #0066FF ≈ 3.6:1 — FAILS 4.5:1 for normal weight. FIX two ways (do both): keep button label ≥18.66px+600 so the 3:1 large-text rule applies (it passes), AND expose --nxp-on-primary + compute on-primary contrast, warning the merchant when their brand color fails (Stripe's accessibleColorOnColorPrimary). For the default theme, darkening primary to ~#0052CC gives #FFF ≈ 5.9:1 if a non-bold label is ever used.
  - Non-text/UI ≥3:1: the resting border #E5E7EB on #FFF ≈ 1.2:1 FAILS 3:1 as a sole affordance. Resolution (industry standard): the border is NOT the sole indicator — label + padding + the focus ring + a subtle inset shadow also identify the field. Document this stance; do NOT darken the resting hairline (that reads heavy/cheap). Error border #DC2626 ≈ 4.4:1 passes.
  - Re-verify every pair on the dark surface; that's why danger/success shift lighter in night.

FOCUS (the crispest divider):
  - Visible focus on EVERY interactive element via :focus-visible (keyboard shows it; mouse can suppress for premium feel — but text inputs keep :focus so it shows while typing). base-styles.ts currently uses :focus on everything — upgrade buttons/tabs to :focus-visible.
  - The 15% ring may not clear SC 1.4.11's 3:1 for the indicator itself. Use the two-layer ring: box-shadow: 0 0 0 1px var(--nxp-color-primary), 0 0 0 3px color-mix(in srgb, var(--nxp-color-primary) 18-25%, transparent) — the solid 1px inner guarantees ≥3:1; raise alpha to 25% (light) / 30–40% (dark). Add outline: 2px solid transparent; outline-offset: 2px as a forced-colors fallback (box-shadow rings are invisible in Windows High Contrast).
  - SC 2.4.11 Focus Not Obscured: the sticky pay bar must not cover a focused field — add scroll-margin-bottom: 64px on fields so they scroll above the bar.

TOUCH: SC 2.5.8 = 24px min; target 44×44 (Apple). Fields clear it; give the brand icon / any close/help glyph a 44px hit area even if the glyph is 20px.

ARIA across the iframe (it's a separate document — do NOT aria-labelledby across the boundary):
  - Each input keeps an in-frame aria-label (already: "Card number"/"Expiry month"/"CVC"). Give the <iframe> element a title="Secure card entry" so it's announced as a labeled region.
  - Wrap the card fields in role="group" aria-label="Card information" (inside the frame's .card-form).
  - Set aria-invalid="true" on a field when validation fails (inside the frame).
  - Error region: role="alert" (form-level submit failures) / aria-live="polite" (inline field errors). card-frame.html error divs already have role="alert" — add aria-live="polite" and keep error TEXT descriptive (not a code) and never color-alone (the visible text + an error icon satisfy "not color alone"). Render the error element always, toggle visibility.
  - Don't fire an SR announcement per keystroke during live 4-4-4-4 grouping.

MOTION a11y: prefers-reduced-motion already honored in card-frame.css + checkout.css; ADD the same block to base-styles.ts. Reduced motion removes motion but NEVER the focus ring or error visibility.
FORCED COLORS: add @media (forced-colors: active) so borders/focus use system colors (border:1px solid CanvasText; the transparent outline becomes visible) — providers that skip this look broken in Windows High Contrast.

## Build plan

Priority order, mapped to real files, each verifiable via the Vite preview (npm run dev -w nexuspay-checkout) + CI (root npm run test/lint/typecheck; theme.test.ts is the unit gate). Phases 1–3 are bug-fix + token foundation (must land first); 4–7 are the premium build; 8 is polish.

P1 — FIX THE BROKEN FOCUS RING + TAB TINTS (correctness, ships premium instantly).
  - base-styles.ts:47,55,122,128,270 — replace every rgba(var(--nxp-color-primary/danger,#hex), alpha) with color-mix(in srgb, var(--nxp-color-primary) 18%, transparent) (and danger equivalent / 6% tab tints). This is a real rendering bug; the iframe side (card-frame.css:85) is already correct.
  - Verify in preview: same-origin email/name field focus ring now matches the iframe card-field ring. Add a theme.test.ts assertion that generated CSS contains color-mix and NOT rgba(var(.
  VERIFY: Vite preview tab through fields — identical 3px ring on both sides.

P2 — EXTEND THE TOKEN SYSTEM (tokens.ts + types.ts + css-properties.ts).
  - Add the new variables to AppearanceVariables (types.ts), DEFAULT_VARIABLES + NIGHT_VARIABLES parity (tokens.ts), and VARIABLE_MAP + emit derived space/type/shadow/easing tokens in generateCSSVariables (css-properties.ts).
  - Promote hard-coded #9CA3AF (base-styles.ts:39, card-frame.css:76) and INPUT_STATES.borderDefault to real tokens.
  - Add easing tokens; reframe ANIMATION/EASING in tokens.ts to reference them; upgrade successCheckmark linear→spring, tabSwitch→standard.
  VERIFY: theme.test.ts — assert each new --nxp-* appears in generated CSS; typecheck passes with new AppearanceVariables keys.

P3 — EXTEND THE IFRAME HANDSHAKE + RULE ALLOWLIST (card-frame.ts, card-frame.html, css-properties.ts).
  - Add the new tokens to the postMessage map (card-frame.html:473 + card-frame.ts mapping ~403-413).
  - Add per-selector CSS-property allowlist + the fonts:[] passthrough (@font-face inside the frame) in generateRuleOverrides; add ResizeObserver→postMessage('resize') in card-frame.html/.ts.
  VERIFY: existing iframe/handshake tests pass; add a test that an unsafe rule prop (position) is dropped.

P4 — ENCAPSULATED CARD GROUP + SHADOW/HAIRLINE (checkout.css + CheckoutPage.tsx).
  - Wrap the PaymentElement in a .nxp-card-group with surface/border/radius-group/shadow-card + the lock-glyph header row. Add the floating page-card around .checkout__form with --nxp-shadow-lg on #F6F8FA.
  VERIFY: preview at desktop + mobile widths.

P5 — REAL BRAND SVGs (elements/icons/index.ts) — replace the 4 <text>-based marks with real vector marks; render on white hairline chips; wire the accepted-card row (muted) + in-field crossfade upgrade (add scale .9→1).
  VERIFY: type "4..."/"5..."/"3..." in preview — correct full-color mark crossfades in.

P6 — BUTTON + STATES (base-styles.ts, checkout.css, CheckoutPage.tsx, SuccessConfirmation.tsx).
  - Replace filter:brightness hover with token lightness shift + translateY(-1px) + shadow + :active scale(.98); add the in-place spinner morph with the 200ms gate + double-submit guard; upgrade the success checkmark stagger (700ms) + spring; add the field shake keyframe; add tabular-nums to amounts.
  - Rename "Secured by NexusPay" → "Your data is encrypted and secure" + add muted "Powered by NexusPay"; fix .checkout__secure-text to use --nxp-color-text-secondary.
  VERIFY: preview success flow; reduced-motion on (OS setting) shows static check.

P7 — STICKY MOBILE PAY BAR + a11y plumbing (CheckoutPage.tsx, checkout.css, card-frame.html, base-styles.ts).
  - Sticky bottom bar with safe-area inset + scroll-margin-bottom on fields; add role="group"/aria-label, aria-live="polite", iframe title; add @media forced-colors + the missing prefers-reduced-motion block to base-styles.ts; :focus-visible upgrade; raise focus-ring alpha to 25% light / 30% dark.
  VERIFY: keyboard-tab the whole form in preview (focus never hidden by the bar); narrow viewport shows the sticky bar.

P8 — POLISH: complete PRESET_NIGHT rules in presets.ts (full dark parity), explicit colorScheme 'auto'|'light'|'dark' + re-postMessage on toggle, optional floating-label v2.
  VERIFY: full theme.test.ts suite green; root npm run test/lint/typecheck clean; visual pass of light+dark+mobile in the Vite preview.

## Research sources


**PROVIDER VISUAL LANGUAGE — checkout/payment widget design systems (Stripe, Adyen, Checkout.com, Square, Braintree, Mollie, Razorpay, Paddle/Lemon Squeezy) + the premium baseline and where the best differentiate** — exemplars: Stripe Payment Element / Appearance API — the most complete token system: themes (stripe/night/flat), inputs spaced|condensed, labels above|floating, derived rem type scale (fontSize3Xs..2Xl), auto-accessible-color-on-color, 2px box-shadow focus ring (0 0 0 2px var(--colorPrimary)) replacing browser outline, layered blue-ink shadow 0px 1px 1px rgba(0,0,0,.03),0px 3px 6px rgba(18,42,66,.02); defaults text #30313d / bg #ffffff / primary #0570de / danger #df1b41; Link one-click chip (+14% returning conversion, 3x faster), Stripe Connect dark / night theme — dark token set bg #14171D, text #C9CED8, primary #0085FF, danger #F23154 (model for NexusPay dark mode), Adyen Drop-in / Card Component — cross-origin iframe styleObject: base{color,fontFamily,fontSize 14px,fontSmoothing antialiased,caretColor,background transparent}, placeholder{fontWeight 400,color #747778}, error, validated; --adyen-sdk-* CSS vars on parent; iframe limited to default system fonts, Checkout.com Frames — hide iframe border + style container on page, BEM modifiers --invalid/--valid/--empty/--focus toggled on parent container, Square Web Payments SDK — CardClassSelectors (.input-container, .is-focus borderColor #006AFF, .is-error #ff1600, input::placeholder #999999), borderRadius 6px, autoFillColor + cardIconColor tokens, 16px max font, Braintree Hosted Fields — styles keyed by 'input'/.number/.cvv/:focus/.valid/.invalid/::placeholder, transition: color 160ms linear inside field, parent classes braintree-hosted-fields-focused/-valid/-invalid, system fonts only

- https://docs.stripe.com/elements/appearance-api

- https://docs.stripe.com/elements/appearance-api.md?api-integration=paymentintents

- https://stripe.com/resources/more/credit-card-checkout-ui-design

- https://docs.stripe.com/payments/link

- https://docs.stripe.com/connect/embedded-appearance-support-dark-mode

- https://help.adyen.com/knowledge/ecommerce-integrations/drop-in-and-components/how-can-i-apply-styling-to-the-inside-of-the-iframes-for-the-card-component-and-dropin

- https://docs.adyen.com/payment-methods/cards/web-component

- https://github.com/adyen/adyen-web

**Micro-interactions & Motion — premium payment checkout widget animation spec (focus rings, floating labels, card-brand transitions, error reveal, processing state, success checkmark, button states, skeletons)** — exemplars: Stripe Payment Element / Elements Appearance API — the reference standard: only motion control is the boolean disableAnimations (so motion is a designed default, not a per-property free-for-all); transition allowed on ~40 selectors via rules; focusBoxShadow default 0 0 0 3px rgba(5,112,222,0.1) + focusOutline:none; .Input base shadow 0px 1px 1px rgba(0,0,0,0.03) (soft depth not glow); floating labels via labels:'floating' with --floating/--resting/--focused/--empty states; honors prefers-reduced-motion; fontSizeBase min 16px on mobile to avoid iOS focus-zoom., Michaël Villar / Stripe Checkout animation essay — defines the payment micro-interaction canon: button state progression 'Pay $25.00' -> spinner -> animated checkmark; contextual field reveals tied to checkbox; SHAKE on validation error to soften frustration; loading animation makes SMS-verify wait feel faster; 'if disabling animations doesn't break the flow, your animations are superfluous.', Linear / Vercel-tier UI — crisp ease-out settle (cubic-bezier(0.16,1,0.3,1)), transform+opacity only, 1px hairlines, restrained palette, micro-press scale(0.98); spring physics for expressive moments only., Square Web Payments SDK — .sq-input--focus styles focused iframe inputs with border-color + box-shadow; same cross-iframe split (outside-iframe: border/margins/width; inside-iframe: font/color/placeholder via InputStyle) that NexusPay's card-frame mirrors., Adyen Drop-in / Components — --adyen-checkout-input-wrapper-focus-border-color for non-iframe focus border; styleObject for inside-iframe card fields (same boundary constraint)., codeshack.io animated success checkmark — exact buildable spec: viewBox 0 0 100 100; circle dasharray 252, check dasharray 50; wrapper pop-in cubic-bezier(0.34,1.56,0.64,1.3) scale to 1.1 then 1; draw-circle 600ms @200ms delay, draw-check 400ms @700ms delay, both cubic-bezier(0.34,1.56,0.64,1); emerald #10b981.

- https://docs.stripe.com/elements/appearance-api.md?api-integration=paymentintents

- https://docs.stripe.com/elements/appearance-api

- https://medium.com/bridge-collection/improve-the-payment-experience-with-animations-3d1b0a9b810e

- https://stripe.com/resources/more/payment-successful-pages

- https://www.joshwcomeau.com/animation/css-transitions/

- https://m1.material.io/motion/duration-easing.html

- https://m3.material.io/styles/motion/easing-and-duration

- https://www.appypie.com/blog/mobile-app-animation-guide

**TRUST & CREDIBILITY — what specific signals in a premium checkout widget build customer confidence and lift conversion, and how to convey safety without looking cluttered or scammy.** — exemplars: Stripe Payment Element / Appearance API — the gold standard for cross-iframe theming: ~60 themeable variables (colorPrimary #0570de, colorDanger #df1b41, inputColorBorder #E0E6EB, focusBoxShadow, fontSizeBase min 16px on mobile) + a fixed allowlist of `.Input`/`.Input--invalid`/`.Label`/`.Error`/`.Tab` rule selectors; in-field brand detection; soft focus ring; Link for 1-click; 'Powered by Stripe' + 'Your data is encrypted and secure' copy guidance; explicit rule to validate on field-complete not mid-keystroke and clear errors on edit., Checkout.com Frames — cleanest cross-iframe split: hide the input border INSIDE the iframe, style the .frame / .frame--focus / .frame--invalid / .frame--valid CONTAINER on the parent, pass a style object (base/valid/invalid/placeholder/focus → color, fontSize, fontFamily, fontWeight, letterSpacing) for the text. Best model for keeping NexusPay's focus ring aligned across the boundary., Square Web Payments SDK (CardClassSelectors) — granular iframe states: .input-container.is-focus / .is-error, input.is-error, .message-text.is-error + .message-icon.is-error for inline errors; fontSize capped at 16px (anti iOS-zoom); good template for NexusPay's error-icon + error-text pairing., Adyen Drop-in/Components — strong brand detection: renders brand icon from typed digits and the matching CVC field; handles co-badged card brand selection per regional regs; box-shadow on brand images., Baymard Institute — the trust research authority: 19% abandon over CC distrust; perceived security = visual 'gut feeling'; visually encapsulate the card fields uniquely; 1–3 trust-signal types beat 0 (+23%) but 7+ underperform (-8%); Norton 36%/McAfee 23% most-trusted seals (recognition > cert)., Linear / Vercel-tier UI craft — two-layer micro-shadows, 1px hairlines, intentional cubic-bezier easings (cubic-bezier(0.16,1,0.3,1) for entrances), crisp non-default focus rings; the 'engineered, not decorated' premium feel NexusPay should match.

- https://baymard.com/blog/perceived-security-of-payment-form

- https://baymard.com/blog/site-seal-trust

- https://baymard.com/research/checkout-usability

- https://www.thesslstore.com/blog/new-baymard-study-how-to-improve-ecommerce-checkout-rates-with-site-seals-checkout-design/

- https://docs.stripe.com/elements/appearance-api

- https://docs.stripe.com/payments/payment-element/best-practices

- https://stripe.com/resources/more/credit-card-checkout-ui-design

- https://stripe.com/resources/more/how-to-create-a-secure-checkout-for-your-business

**Theming, Dark Mode, Responsive & Accessibility — buildable spec for the NexusPay checkout-sdk premium card widget** — exemplars: Stripe Appearance API (Payment Element / Elements) — the definitive 3-layer model NexusPay already mirrors (theme + variables + rules). ~80 variables incl. colorPrimary #0570de, spacingUnit 2px, borderRadius 4px, fontSizeBase scaled in rem; auto-derived accessibleColorOnColorPrimary for button-text contrast; strict rule constraints (no descendant/private selectors; color vars must be solid hex). Best-in-class for the iframe-seamless single input., Stripe Connect embedded components — the most complete PRODUCTION dark palette to copy structure from: bg #14171D, text #C9CED8, secondaryText #8C99AD, border #2B3039, primary #0085FF, danger #F23154, tinted-dark semantic badges (success bg #152207/text #3EAE20). Demonstrates desaturated-on-tinted-dark (not neon) and the explicit isDarkMode toggle + update() re-theming pattern., Stripe mobile-checkout-UI guidance — single column, labels above (not placeholders), 44×44 touch targets, ≥16px input font to stop iOS zoom, sticky bottom CTA labeled 'Pay $42.98', correct inputmode/autocomplete, as-you-type 4-4-4-4 grouping, card-scan support., Adyen Web v6 Drop-in/Components — CSS-custom-property theming overridable at :root or per-element; in-iframe card styling via styleObject with base/error/placeholder/validated states; explicitly restricts in-iframe fonts to an allowlist (the safe-subset principle)., Square Web Payments SDK — cleanest reference for the iframe-SAFE rule surface: CardClassSelectors (input, input.is-focus, input.is-error, input::placeholder, .input-container[.is-focus/.is-error], .message-text[.is-error], .message-icon) limited to color/backgroundColor/fontFamily/fontSize/fontWeight/borderColor/borderRadius. Use as NexusPay's rule allowlist; supports @media queries for responsive in-frame styling., Checkout.com Flow — alternative semantic token vocabulary worth borrowing: separates colorFormBackground/colorFormBorder (the card) from colorBackground/colorBorder (the page), colorAction (links) vs colorPrimary (buttons), colorOutline (focus), colorDisabledForeground; appearance + componentOptions objects; web-font passthrough. Real values: colorAction #5E48FC, colorBorder #68686C.

- https://docs.stripe.com/elements/appearance-api

- https://docs.stripe.com/elements/appearance-api.md?api-integration=paymentintents

- https://docs.stripe.com/connect/embedded-appearance-support-dark-mode

- https://stripe.com/resources/more/mobile-checkout-ui

- https://stripe.com/payments/elements

- https://docs.adyen.com/online-payments/upgrade-your-integration/upgrade-to-web-v6

- https://help.adyen.com/knowledge/ecommerce-integrations/drop-in-and-components/how-can-i-apply-styling-to-the-inside-of-the-iframes-for-the-card-component-and-dropin

- https://github.com/Adyen/adyen-web

**PREMIUM CSS/SVG CRAFT (2026) — depth, hairlines, focus rings, motion, card-brand SVGs, and styling across the cross-origin PCI iframe boundary, mapped onto the NexusPay --nxp-* token system.** — exemplars: Stripe Payment Element / Appearance API — the gold-standard cross-iframe styling model: theme(stripe|night|flat) + variables(CSS-custom-prop-like, support var() refs) + rules(per-selector CSS-property ALLOWLIST). Layered focus ring + tab box-shadow examples come straight from their docs. Real-time inline validation, brand-icon crossfade, Pay->spinner->checkmark microinteraction. Exposes Light/Normal/Medium/Bold weights, focusBoxShadow+focusOutline, gridRow/ColumnSpacing, tabSpacing, fontSize ladder (3Xs..2Xl)., Stripe Link — one-click returning-customer autofill; compresses to single page/modal, optional fields removed; 6s checkout (9x faster), +7–14% conversion on logged-in users. The express/trust signal NexusPay should surface above the card form., Adyen Secured Fields — styles object {base,error,placeholder,validated}; narrow text-only allowlist inside iframe; hard limit: only default font families inside the iframe (web-font cross-origin limitation); outer chrome via --adyen-sdk-* tokens., Braintree/PayPal Hosted Fields — styles keyed by input/.valid/.invalid/.number; text+box allowlist only (no border/bg/radius) — alternative architecture: style the wrapper in the parent, pass only TEXT styles into the iframe; unsupported props dropped with console warning., Square Web Payments SDK — CardClassSelectors (input, input.is-focus, input.is-error, .input-container[.is-focus|.is-error], .message-text, .message-icon); props incl placeholderColor, cardIconColor, borderRadius; HARD fontSize cap 16px to stop iOS zoom; dark example #2D2D2D/#FFF/6px., Checkout.com Flow (Frames EOL 2026-06-30) — designTokens: colorAction/Background/Border/Disabled/Error/FormBackground/FormBorder/Inverse/Outline/Primary/Secondary/Success + borderRadius array + per-component typography.

- https://docs.stripe.com/elements/appearance-api

- https://docs.stripe.com/elements/appearance-api.md?api-integration=paymentintents

- https://stripe.com/payments/elements

- https://stripe.com/payments/link

- https://stripe.com/resources/more/checkout-ui-strategies-for-faster-and-more-intuitive-transactions

- https://creatoreconomy.so/p/how-stripe-crafts-quality-products-katie-dill

- https://www.illustration.app/blog/stripe-payment-ux-gold-standard

- https://help.adyen.com/knowledge/ecommerce-integrations/drop-in-and-components/how-can-i-apply-styling-to-the-inside-of-the-iframes-for-the-card-component-and-dropin