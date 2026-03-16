# NexusPay Checkout SDK Design System

Version 1.0 | Last updated 2026-03-15

---

## 1. Appearance Type

The SDK exposes a typed `Appearance` object that merchants pass at initialization to customize every visual aspect of the checkout UI.

```typescript
interface Appearance {
  theme: 'default' | 'night' | 'flat' | 'none';
  variables?: AppearanceVariables;
  rules?: Record<string, Record<string, string>>;
}

interface AppearanceVariables {
  /** Primary action / accent color */
  colorPrimary: string;        // default: '#0066FF'
  /** Surface / card background */
  colorBackground: string;     // default: '#FFFFFF'
  /** Primary text color */
  colorText: string;            // default: '#1A1A2E'
  /** Destructive / validation error */
  colorDanger: string;          // default: '#DC2626'
  /** Positive confirmation */
  colorSuccess: string;         // default: '#16A34A'
  /** Font stack */
  fontFamily: string;           // default: "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif"
  /** Base font size (all relative sizes derived from this) */
  fontSizeBase: string;         // default: '16px'
  /** Border radius for inputs, cards, buttons */
  borderRadius: string;         // default: '8px'
  /** Base spacing unit (margins/paddings are multiples of this) */
  spacingUnit: string;          // default: '4px'
  /** Normal weight */
  fontWeightNormal: number;     // default: 400
  /** Bold / emphasis weight */
  fontWeightBold: number;       // default: 600
}
```

### Rules Override

The `rules` object maps CSS-like selectors to style declarations, allowing surgical overrides beyond variables.

```typescript
const appearance: Appearance = {
  theme: 'default',
  variables: {
    colorPrimary: '#7C3AED',
  },
  rules: {
    '.Input': {
      'border': '1px solid #D4D4D8',
      'font-size': '14px',
    },
    '.Input:focus': {
      'border-color': '#7C3AED',
      'box-shadow': '0 0 0 3px rgba(124, 58, 237, 0.15)',
    },
    '.Input--invalid': {
      'border-color': '#DC2626',
    },
    '.Label': {
      'font-weight': '500',
      'font-size': '14px',
      'color': '#374151',
    },
    '.Error': {
      'color': '#DC2626',
      'font-size': '13px',
    },
    '.Tab': {
      'border-bottom': '2px solid transparent',
      'padding': '12px 16px',
    },
    '.Tab--selected': {
      'border-bottom-color': '#7C3AED',
      'background-color': 'rgba(124, 58, 237, 0.04)',
    },
    '.Tab:hover': {
      'background-color': 'rgba(124, 58, 237, 0.04)',
    },
  },
};
```

Supported selectors:

| Selector | Target |
|---|---|
| `.Input` | All text inputs (card number, expiry, CVC, postal) |
| `.Input:focus` | Focused input state |
| `.Input:hover` | Hovered input state |
| `.Input--invalid` | Input with a validation error |
| `.Input:disabled` | Disabled input |
| `.Label` | Field labels |
| `.Error` | Inline validation error messages |
| `.Tab` | Payment method tab (base) |
| `.Tab--selected` | Currently active tab |
| `.Tab:hover` | Hovered tab |
| `.Tab:disabled` | Disabled / unavailable tab |
| `.Button` | Submit / pay button |
| `.Button:hover` | Hovered button |
| `.Button:disabled` | Disabled button |
| `.Button--loading` | Button in loading state |
| `.Button--success` | Button showing success confirmation |

---

## 2. CSS Custom Properties

Every `AppearanceVariables` key maps to a `--nxp-*` CSS custom property injected into the iframe root. Components reference only these properties, never raw values.

| Variable | CSS Custom Property | Default Value |
|---|---|---|
| `colorPrimary` | `--nxp-color-primary` | `#0066FF` |
| `colorBackground` | `--nxp-color-background` | `#FFFFFF` |
| `colorText` | `--nxp-color-text` | `#1A1A2E` |
| `colorDanger` | `--nxp-color-danger` | `#DC2626` |
| `colorSuccess` | `--nxp-color-success` | `#16A34A` |
| `fontFamily` | `--nxp-font-family` | `system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif` |
| `fontSizeBase` | `--nxp-font-size-base` | `16px` |
| `borderRadius` | `--nxp-border-radius` | `8px` |
| `spacingUnit` | `--nxp-spacing-unit` | `4px` |
| `fontWeightNormal` | `--nxp-font-weight-normal` | `400` |
| `fontWeightBold` | `--nxp-font-weight-bold` | `600` |

### Derived Properties

Generated at runtime from the base variables:

| CSS Custom Property | Derivation |
|---|---|
| `--nxp-color-primary-rgb` | RGB channels of `colorPrimary` (e.g. `0, 102, 255`) for use in `rgba()` |
| `--nxp-color-danger-rgb` | RGB channels of `colorDanger` |
| `--nxp-color-success-rgb` | RGB channels of `colorSuccess` |
| `--nxp-color-text-secondary` | `colorText` at 60% opacity |
| `--nxp-color-border` | Theme-specific (light: `#E5E7EB`, dark: `#334155`) |
| `--nxp-color-border-hover` | Theme-specific (light: `#D1D5DB`, dark: `#475569`) |
| `--nxp-spacing-2x` | `calc(var(--nxp-spacing-unit) * 2)` = `8px` |
| `--nxp-spacing-3x` | `calc(var(--nxp-spacing-unit) * 3)` = `12px` |
| `--nxp-spacing-4x` | `calc(var(--nxp-spacing-unit) * 4)` = `16px` |
| `--nxp-spacing-6x` | `calc(var(--nxp-spacing-unit) * 6)` = `24px` |
| `--nxp-spacing-8x` | `calc(var(--nxp-spacing-unit) * 8)` = `32px` |
| `--nxp-font-size-sm` | `calc(var(--nxp-font-size-base) * 0.8125)` = `13px` |
| `--nxp-font-size-lg` | `calc(var(--nxp-font-size-base) * 1.125)` = `18px` |
| `--nxp-shadow-focus` | `0 0 0 3px rgba(var(--nxp-color-primary-rgb), 0.15)` |
| `--nxp-shadow-error` | `0 0 0 3px rgba(var(--nxp-color-danger-rgb), 0.15)` |

### Injection

```css
:root {
  --nxp-color-primary: #0066FF;
  --nxp-color-primary-rgb: 0, 102, 255;
  --nxp-color-background: #FFFFFF;
  --nxp-color-text: #1A1A2E;
  --nxp-color-danger: #DC2626;
  --nxp-color-danger-rgb: 220, 38, 38;
  --nxp-color-success: #16A34A;
  --nxp-color-success-rgb: 22, 163, 74;
  --nxp-font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  --nxp-font-size-base: 16px;
  --nxp-border-radius: 8px;
  --nxp-spacing-unit: 4px;
  --nxp-font-weight-normal: 400;
  --nxp-font-weight-bold: 600;
}
```

---

## 3. Theme Presets

### default (Light)

```typescript
const defaultTheme: AppearanceVariables = {
  colorPrimary:    '#0066FF',
  colorBackground: '#FFFFFF',
  colorText:       '#1A1A2E',
  colorDanger:     '#DC2626',
  colorSuccess:    '#16A34A',
  fontFamily:      "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif",
  fontSizeBase:    '16px',
  borderRadius:    '8px',
  spacingUnit:     '4px',
  fontWeightNormal: 400,
  fontWeightBold:   600,
};
```

| Token | Value |
|---|---|
| Background | `#FFFFFF` |
| Surface (card, input bg) | `#FFFFFF` |
| Primary | `#0066FF` |
| Text primary | `#1A1A2E` |
| Text secondary | `rgba(26, 26, 46, 0.6)` |
| Border | `#E5E7EB` |
| Border hover | `#D1D5DB` |
| Input shadow (focus) | `0 0 0 3px rgba(0, 102, 255, 0.15)` |
| Card shadow | `0 1px 3px rgba(0, 0, 0, 0.08)` |

### night (Dark)

```typescript
const nightTheme: AppearanceVariables = {
  colorPrimary:    '#3B82F6',
  colorBackground: '#0F172A',
  colorText:       '#E2E8F0',
  colorDanger:     '#EF4444',
  colorSuccess:    '#22C55E',
  fontFamily:      "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif",
  fontSizeBase:    '16px',
  borderRadius:    '8px',
  spacingUnit:     '4px',
  fontWeightNormal: 400,
  fontWeightBold:   600,
};
```

| Token | Value |
|---|---|
| Background | `#0F172A` |
| Surface (card, input bg) | `#1E293B` |
| Primary | `#3B82F6` |
| Text primary | `#E2E8F0` |
| Text secondary | `rgba(226, 232, 240, 0.6)` |
| Border | `#334155` |
| Border hover | `#475569` |
| Input shadow (focus) | `0 0 0 3px rgba(59, 130, 246, 0.25)` |
| Card shadow | `0 1px 3px rgba(0, 0, 0, 0.3)` |

### flat (Minimal)

```typescript
const flatTheme: AppearanceVariables = {
  colorPrimary:    '#0066FF',
  colorBackground: '#FFFFFF',
  colorText:       '#1A1A2E',
  colorDanger:     '#DC2626',
  colorSuccess:    '#16A34A',
  fontFamily:      "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif",
  fontSizeBase:    '16px',
  borderRadius:    '0px',
  spacingUnit:     '4px',
  fontWeightNormal: 400,
  fontWeightBold:   600,
};
```

| Token | Value |
|---|---|
| Background | `#FFFFFF` |
| Surface | `#FFFFFF` |
| Primary | `#0066FF` |
| Border | `#E5E7EB` |
| Border radius | `0px` (square corners everywhere) |
| All shadows | `none` |
| Card shadow | `none` |
| Input shadow (focus) | `none` (uses `border-color` change only) |

When `theme: 'none'` is set, no preset variables are applied. The SDK injects only the variables explicitly provided by the merchant; any missing property falls back to the browser default.

---

## 4. Component States

### Input

| State | Border | Background | Shadow | Text Color | Cursor | Transition |
|---|---|---|---|---|---|---|
| Default | `1px solid #E5E7EB` | `var(--nxp-color-background)` | `none` | `var(--nxp-color-text)` | `text` | `border-color 150ms ease, box-shadow 150ms ease` |
| Hover | `1px solid #D1D5DB` | `var(--nxp-color-background)` | `none` | `var(--nxp-color-text)` | `text` | |
| Focus | `1px solid var(--nxp-color-primary)` | `var(--nxp-color-background)` | `0 0 0 3px rgba(var(--nxp-color-primary-rgb), 0.15)` | `var(--nxp-color-text)` | `text` | |
| Error | `1px solid var(--nxp-color-danger)` | `var(--nxp-color-background)` | `0 0 0 3px rgba(var(--nxp-color-danger-rgb), 0.15)` | `var(--nxp-color-text)` | `text` | |
| Disabled | `1px solid #E5E7EB` | `#F9FAFB` | `none` | `rgba(var(--nxp-color-text), 0.5)` | `not-allowed` | |

```css
.Input {
  height: 44px;
  padding: 0 var(--nxp-spacing-3x);
  border: 1px solid var(--nxp-color-border);
  border-radius: var(--nxp-border-radius);
  background: var(--nxp-color-background);
  color: var(--nxp-color-text);
  font-family: var(--nxp-font-family);
  font-size: var(--nxp-font-size-base);
  font-weight: var(--nxp-font-weight-normal);
  outline: none;
  transition: border-color 150ms ease, box-shadow 150ms ease;
}

.Input:hover {
  border-color: var(--nxp-color-border-hover);
}

.Input:focus {
  border-color: var(--nxp-color-primary);
  box-shadow: var(--nxp-shadow-focus);
}

.Input--invalid {
  border-color: var(--nxp-color-danger);
  box-shadow: var(--nxp-shadow-error);
}

.Input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background: #F9FAFB;
}
```

Error messages appear below the input:

```css
.Error {
  color: var(--nxp-color-danger);
  font-size: var(--nxp-font-size-sm);
  margin-top: var(--nxp-spacing-unit);
  animation: nxp-slide-in 200ms ease-out;
}

@keyframes nxp-slide-in {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
```

### Tab

| State | Border Bottom | Background | Text Color | Cursor |
|---|---|---|---|---|
| Default | `2px solid transparent` | `transparent` | `var(--nxp-color-text-secondary)` | `pointer` |
| Hover | `2px solid transparent` | `rgba(var(--nxp-color-primary-rgb), 0.04)` | `var(--nxp-color-text)` | `pointer` |
| Selected | `2px solid var(--nxp-color-primary)` | `rgba(var(--nxp-color-primary-rgb), 0.04)` | `var(--nxp-color-primary)` | `default` |
| Disabled | `2px solid transparent` | `transparent` | `rgba(var(--nxp-color-text), 0.3)` | `not-allowed` |

```css
.Tab {
  display: flex;
  align-items: center;
  gap: var(--nxp-spacing-2x);
  padding: var(--nxp-spacing-3x) var(--nxp-spacing-4x);
  border: none;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--nxp-color-text-secondary);
  font-family: var(--nxp-font-family);
  font-size: var(--nxp-font-size-base);
  font-weight: var(--nxp-font-weight-normal);
  cursor: pointer;
  transition: background-color 150ms ease, color 150ms ease, border-color 150ms ease;
}

.Tab:hover {
  background-color: rgba(var(--nxp-color-primary-rgb), 0.04);
  color: var(--nxp-color-text);
}

.Tab--selected {
  border-bottom-color: var(--nxp-color-primary);
  background-color: rgba(var(--nxp-color-primary-rgb), 0.04);
  color: var(--nxp-color-primary);
  font-weight: var(--nxp-font-weight-bold);
}

.Tab:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}
```

### Button (Submit / Pay)

| State | Background | Shadow | Text | Cursor | Additional |
|---|---|---|---|---|---|
| Default | `var(--nxp-color-primary)` | `0 1px 2px rgba(0,0,0,0.08)` | `#FFFFFF` | `pointer` | |
| Hover | `colorPrimary darkened 10%` | `0 2px 4px rgba(0,0,0,0.12)` | `#FFFFFF` | `pointer` | `filter: brightness(0.9)` |
| Loading | `var(--nxp-color-primary)` | `0 1px 2px rgba(0,0,0,0.08)` | hidden | `wait` | Spinner overlay, 300ms ease fade-in |
| Disabled | `var(--nxp-color-primary)` | `none` | `#FFFFFF` | `not-allowed` | `opacity: 0.5` |
| Success | `var(--nxp-color-success)` | `none` | `#FFFFFF` | `default` | Checkmark SVG, 600ms stroke animation |

```css
.Button {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--nxp-spacing-2x);
  width: 100%;
  height: 48px;
  padding: 0 var(--nxp-spacing-6x);
  border: none;
  border-radius: var(--nxp-border-radius);
  background: var(--nxp-color-primary);
  color: #FFFFFF;
  font-family: var(--nxp-font-family);
  font-size: var(--nxp-font-size-base);
  font-weight: var(--nxp-font-weight-bold);
  cursor: pointer;
  transition: filter 150ms ease, box-shadow 150ms ease, background-color 300ms ease;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
}

.Button:hover {
  filter: brightness(0.9);
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.12);
}

.Button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  box-shadow: none;
}

.Button--loading {
  cursor: wait;
  position: relative;
}

.Button--loading .Button__label {
  visibility: hidden;
}

.Button--loading::after {
  content: '';
  position: absolute;
  width: 20px;
  height: 20px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #FFFFFF;
  border-radius: 50%;
  animation: nxp-spin 600ms linear infinite;
}

.Button--success {
  background: var(--nxp-color-success);
  transition: background-color 300ms ease;
}

@keyframes nxp-spin {
  to { transform: rotate(360deg); }
}
```

---

## 5. Card Input Micro-Interactions

### Number Grouping

Card numbers are visually grouped with spaces. The SDK auto-detects the brand from the first digits and applies the appropriate mask.

| Brand | Grouping | Display | Max Digits |
|---|---|---|---|
| Visa, Mastercard, Discover, JCB, UnionPay | 4-4-4-4 | `4242 4242 4242 4242` | 16 |
| Amex | 4-6-5 | `3782 822463 10005` | 15 |
| Diners Club | 4-6-4 | `3056 930902 5904` | 14 |

**Cursor behavior:** When the user types the last digit before a space boundary, the cursor advances past the space automatically. When backspacing, the cursor skips back over the space. Selection ranges that span space boundaries must preserve correct digit positions in the underlying unformatted value.

### Brand Icon

The brand icon sits right-aligned inside the card number field, 12px from the right edge.

```css
.CardNumber__brand {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  width: 32px;
  height: 20px;
  transition: opacity 150ms ease-out;
}

.CardNumber__brand--entering {
  animation: nxp-crossfade-in 150ms ease-out;
}

@keyframes nxp-crossfade-in {
  from { opacity: 0; }
  to   { opacity: 1; }
}
```

When the brand changes (e.g., user clears and types a new number), the outgoing icon fades to 0 opacity over 150ms, then the incoming icon fades in over 150ms. Total crossfade: 150ms (overlapped).

### Expiry Field

- Input mask: `MM / YY` (the ` / ` separator is injected automatically).
- After the user types 2 digits for the month (01-12), focus auto-advances to the year portion.
- Invalid months (00, 13+) trigger inline error immediately.
- Placeholder text: `MM / YY` in `var(--nxp-color-text-secondary)`.

### CVC Field

| Brand | CVC Length | Placeholder |
|---|---|---|
| Amex | 4 digits | `0000` |
| All others | 3 digits | `000` |

The CVC field displays a card-back icon to the right:

```css
.CVC__icon {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  width: 24px;
  height: 16px;
  perspective: 400px;
}

.CVC__icon-card {
  transform: rotateY(180deg);
  transition: transform 300ms ease;
  transform-style: preserve-3d;
  backface-visibility: hidden;
}
```

When the CVC input receives focus, the card icon performs a CSS 3D Y-axis flip (rotateY 0 to 180deg, 300ms ease) to reveal the back of the card with the CVC strip highlighted.

### Inline Validation Errors

Errors appear below the field with a slide-in animation:

- **Animation:** `translateY(-4px)` to `translateY(0)`, `opacity: 0` to `1`
- **Duration:** 200ms
- **Easing:** ease-out
- **Color:** `var(--nxp-color-danger)` (`#DC2626`)
- **Font size:** `var(--nxp-font-size-sm)` (13px)
- **Margin top:** `var(--nxp-spacing-unit)` (4px)

Errors are shown on blur (not on every keystroke) to avoid frustrating the user mid-input. The exception is the card number field, which validates the Luhn checksum once the expected length is reached.

### Focus Ring

All card sub-fields share the same focus style:

```css
border-color: var(--nxp-color-primary);
box-shadow: 0 0 0 3px rgba(var(--nxp-color-primary-rgb), 0.15);
transition: border-color 150ms ease, box-shadow 150ms ease;
```

---

## 6. Payment Element Tabs

### Desktop Layout (>= 640px)

Tabs render as a horizontal bar. Each tab contains an icon (network logo as inline SVG, 24x16px) and a text label.

```
[ (visa-icon) Card ]  [ (bank-icon) Bank Transfer ]  [ (apple-icon) Apple Pay ]
```

```css
.PaymentTabs {
  display: flex;
  flex-direction: row;
  border-bottom: 1px solid var(--nxp-color-border);
  gap: 0;
}

.PaymentTabs .Tab {
  flex: 0 0 auto;
  white-space: nowrap;
}

.PaymentTabs .Tab__icon {
  width: 24px;
  height: 16px;
  flex-shrink: 0;
}
```

### Mobile Layout (< 640px)

Tabs render as a vertical radio-button-style list. Each row is a full-width option with a 44px minimum touch target (WCAG 2.5.5).

```css
.PaymentTabs--mobile {
  display: flex;
  flex-direction: column;
  gap: var(--nxp-spacing-2x);
}

.PaymentTabs--mobile .Tab {
  min-height: 44px;
  padding: var(--nxp-spacing-3x) var(--nxp-spacing-4x);
  border: 1px solid var(--nxp-color-border);
  border-radius: var(--nxp-border-radius);
  border-bottom: none;
}

.PaymentTabs--mobile .Tab--selected {
  border-color: var(--nxp-color-primary);
  border-bottom: none;
  background-color: rgba(var(--nxp-color-primary-rgb), 0.04);
}
```

A radio circle indicator (16px diameter) is rendered to the left of each option. Selected state fills the circle with `colorPrimary`.

### Tab Content Switching

When the user selects a different payment method, the outgoing panel fades out and the incoming panel fades in:

```css
.TabPanel--entering {
  animation: nxp-tab-fade-in 200ms ease-in-out;
}

.TabPanel--exiting {
  animation: nxp-tab-fade-out 200ms ease-in-out;
}

@keyframes nxp-tab-fade-in {
  from { opacity: 0; transform: translateY(4px); }
  to   { opacity: 1; transform: translateY(0); }
}

@keyframes nxp-tab-fade-out {
  from { opacity: 1; transform: translateY(0); }
  to   { opacity: 0; transform: translateY(-4px); }
}
```

Duration: 200ms. Easing: ease-in-out. The exit and enter animations run concurrently (crossfade).

---

## 7. card-frame.html Specs

The card input fields render inside a sandboxed `<iframe>` (`card-frame.html`) for PCI DSS compliance. All sensitive card data stays within this frame.

### Font Stack

```css
html {
  font-size: 16px;
}

body {
  font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}
```

### Units

All sizing uses `rem` relative to the `html { font-size: 16px }` base. This ensures consistent scaling if the merchant or user overrides browser font size.

| Token | rem | px equivalent |
|---|---|---|
| Input height | `2.75rem` | 44px |
| Font size base | `1rem` | 16px |
| Font size sm | `0.8125rem` | 13px |
| Spacing unit | `0.25rem` | 4px |
| Border radius | `0.5rem` | 8px |

### Dark Mode

The frame respects `prefers-color-scheme` when `theme: 'default'` is set, automatically switching to `night` palette values.

```css
@media (prefers-color-scheme: dark) {
  :root {
    --nxp-color-background: #0F172A;
    --nxp-color-text: #E2E8F0;
    --nxp-color-border: #334155;
    --nxp-color-border-hover: #475569;
    --nxp-color-primary: #3B82F6;
    --nxp-color-primary-rgb: 59, 130, 246;
  }
}
```

This media query is **only** injected when `theme` is `'default'`. Explicit theme choices (`'night'`, `'flat'`, `'none'`) override and disable the media query.

### RTL Support

```html
<input dir="auto" type="text" inputmode="numeric" />
```

All text inputs use `dir="auto"` so the field adapts to the user's locale. Layout direction for labels and error messages is inherited from the parent merchant page via `postMessage`.

### Autofill Styling

Browsers override input styles on autofill. The frame normalizes this:

```css
input:-webkit-autofill,
input:-webkit-autofill:hover,
input:-webkit-autofill:focus {
  -webkit-text-fill-color: var(--nxp-color-text);
  -webkit-box-shadow: 0 0 0 1000px var(--nxp-color-background) inset;
  transition: background-color 5000s ease-in-out 0s;
  font-family: var(--nxp-font-family);
  font-size: var(--nxp-font-size-base);
}
```

---

## 8. Animation Timings

| Animation | Duration | Easing | Usage |
|---|---|---|---|
| Card brand crossfade | 150ms | `ease-out` | Brand icon swap in card number field |
| Error slide-in | 200ms | `ease-out` | Validation message appearing below a field |
| Tab switch (crossfade) | 200ms | `ease-in-out` | Payment method panel enter/exit |
| Submit loading | 300ms | `ease` | Button label fade-out, spinner fade-in |
| Success checkmark | 600ms | `linear` | SVG stroke-dasharray draw animation |
| Skeleton shimmer | 1.5s | `linear` (infinite) | Loading placeholder before iframe ready |
| Focus ring | 150ms | `ease` | Border-color and box-shadow transition |
| Button hover | 150ms | `ease` | Filter brightness + shadow change |
| CVC card flip | 300ms | `ease` | 3D rotateY on CVC focus |

### Skeleton Shimmer

While the iframe loads, the host page renders skeleton placeholders:

```css
.Skeleton {
  background: linear-gradient(
    90deg,
    var(--nxp-color-border) 25%,
    #F3F4F6 50%,
    var(--nxp-color-border) 75%
  );
  background-size: 200% 100%;
  animation: nxp-shimmer 1.5s linear infinite;
  border-radius: var(--nxp-border-radius);
}

@keyframes nxp-shimmer {
  0%   { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
```

### Success Checkmark

```css
.Checkmark {
  stroke: #FFFFFF;
  stroke-width: 3;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-dasharray: 24;
  stroke-dashoffset: 24;
  animation: nxp-check-draw 600ms linear forwards;
}

@keyframes nxp-check-draw {
  to { stroke-dashoffset: 0; }
}
```

### Reduced Motion

All animations respect `prefers-reduced-motion`:

```css
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
```

---

## 9. Responsive Breakpoints

| Breakpoint | Width | Layout | Notes |
|---|---|---|---|
| Mobile | `< 640px` | Single-column | Radio-button method list, 44px touch targets, stacked fields |
| Desktop | `>= 640px` | Two-column (hosted checkout) | Horizontal tabs, side-by-side expiry/CVC |

### Mobile (< 640px)

```css
@media (max-width: 639px) {
  .CheckoutForm {
    display: flex;
    flex-direction: column;
    gap: var(--nxp-spacing-4x);
    padding: var(--nxp-spacing-4x);
  }

  .CardFields {
    display: flex;
    flex-direction: column;
    gap: var(--nxp-spacing-3x);
  }

  /* Expiry and CVC stack vertically on small screens */
  .CardFields__row {
    flex-direction: column;
  }

  .Tab {
    min-height: 44px;  /* WCAG 2.5.5 minimum touch target */
    min-width: 44px;
  }
}
```

### Desktop (>= 640px)

```css
@media (min-width: 640px) {
  .CheckoutForm {
    display: flex;
    flex-direction: column;
    gap: var(--nxp-spacing-4x);
    max-width: 480px;
  }

  /* Expiry and CVC sit side by side */
  .CardFields__row {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--nxp-spacing-3x);
  }

  .PaymentTabs {
    flex-direction: row;
  }
}
```

---

## 10. Icon & Asset Kit

All icons are inline SVGs using `currentColor` for fill/stroke so they automatically adapt to the active theme.

### Card Brand SVGs

| Brand | Filename | Dimensions | Notes |
|---|---|---|---|
| Visa | `visa.svg` | 32x20 | Blue wordmark |
| Mastercard | `mastercard.svg` | 32x20 | Overlapping circles |
| Amex | `amex.svg` | 32x20 | Blue box wordmark |
| Discover | `discover.svg` | 32x20 | Orange/black logotype |
| JCB | `jcb.svg` | 32x20 | Tri-color emblem |
| UnionPay | `unionpay.svg` | 32x20 | Red/blue/green mark |
| Maestro | `maestro.svg` | 32x20 | Blue/red circles |
| Diners Club | `diners.svg` | 32x20 | Globe emblem |
| Generic (unknown) | `card-generic.svg` | 32x20 | Gray card outline |

Brand SVGs use their official brand colors (not `currentColor`) since brand guidelines require fixed colors. They are embedded as inline `<svg>` elements, not `<img>` tags, to avoid additional HTTP requests from the iframe.

### Payment Method Icons

| Icon | Filename | Dimensions | Fill |
|---|---|---|---|
| Apple Pay | `apple-pay.svg` | 24x16 | `currentColor` (dark mark on light, white on dark) |
| Google Pay | `google-pay.svg` | 24x16 | Official multi-color |
| Bank / ACH | `bank.svg` | 24x16 | `currentColor` |
| BNPL (Buy Now, Pay Later) | `bnpl.svg` | 24x16 | `currentColor` |

### UI Icons

| Icon | Filename | Size | Usage |
|---|---|---|---|
| Lock | `lock.svg` | 16x16 | Submit button prefix, security indicator |
| Checkmark | `checkmark.svg` | 20x20 | Success state, animated stroke |
| Error (circle-exclamation) | `error.svg` | 16x16 | Error messages prefix |
| Caret (chevron-down) | `caret.svg` | 12x12 | Dropdown indicators, collapsible summary toggle |
| Spinner | CSS-only | 20x20 | Loading state (no SVG, pure CSS border animation) |

All UI icons use `currentColor`:

```html
<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
  <path d="..." stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
</svg>
```

---

## 11. Hosted Checkout Layout

The hosted checkout page (`/checkout/{session_id}`) is a full-page payment form served by NexusPay. Merchants redirect customers here when they do not embed the SDK inline.

### Desktop Layout (>= 640px)

```
+------------------------------------------------------------+
|  [Logo]  Business Name                                     |
+----------------------------+-------------------------------+
|                            |                               |
|   Payment Form             |   Order Summary               |
|   max-width: 480px         |   max-width: 400px            |
|                            |                               |
|   [Card / Bank tabs]       |   Item 1          $XX.XX      |
|   [Card number       ]     |   Item 2          $XX.XX      |
|   [Expiry] [CVC]          |   -------------------------    |
|   [Country / Postal  ]     |   Subtotal        $XX.XX      |
|                            |   Tax              $X.XX       |
|   [  Pay $XX.XX  ]        |   Total           $XXX.XX      |
|                            |                               |
+----------------------------+-------------------------------+
```

```css
.HostedCheckout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  max-width: 960px;
  margin: 0 auto;
  padding: var(--nxp-spacing-8x);
  gap: var(--nxp-spacing-8x);
}

.HostedCheckout__form {
  max-width: 480px;
}

.HostedCheckout__summary {
  max-width: 400px;
  padding: var(--nxp-spacing-6x);
  background: #F9FAFB;
  border-radius: var(--nxp-border-radius);
  border: 1px solid var(--nxp-color-border);
}
```

### Mobile Layout (< 640px)

Single column. The order summary is collapsed behind a toggle:

```css
@media (max-width: 639px) {
  .HostedCheckout {
    grid-template-columns: 1fr;
    padding: var(--nxp-spacing-4x);
    gap: var(--nxp-spacing-4x);
  }

  .HostedCheckout__summary {
    max-width: 100%;
  }

  .HostedCheckout__summary-toggle {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--nxp-spacing-3x) 0;
    cursor: pointer;
    min-height: 44px;
  }

  .HostedCheckout__summary-content {
    overflow: hidden;
    max-height: 0;
    transition: max-height 300ms ease;
  }

  .HostedCheckout__summary-content--open {
    max-height: 500px;
  }
}
```

### Merchant Branding

Merchants configure branding via the Checkout Session API:

```typescript
interface CheckoutBranding {
  logo_url: string;          // URL to merchant logo (max 128x48px display)
  accent_color: string;      // Overrides colorPrimary (hex)
  background_color: string;  // Page background (hex)
  business_name: string;     // Displayed in header
}
```

- Logo renders at max 128x48px, `object-fit: contain`.
- `accent_color` is injected as `--nxp-color-primary`.
- `background_color` is set on `<body>`.
- `business_name` renders in the header at `font-size: 18px`, `font-weight: 600`.

### Submit Button

```
[ (lock-icon) Pay $149.00 ]
```

- Full width within the form column.
- Height: 48px.
- Text: "Pay " followed by the formatted amount with currency symbol.
- Lock icon (16x16, `currentColor`) left of text.
- WCAG AA contrast: the button text (`#FFFFFF`) against `colorPrimary` (`#0066FF`) has a contrast ratio of 4.68:1 (passes AA for normal text). Night theme `#3B82F6` against `#FFFFFF` is 3.96:1 (passes AA for large text; button text at 16px bold qualifies).

### Success State

After successful payment, the form transitions to a confirmation view:

```css
.Success {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--nxp-spacing-4x);
  padding: var(--nxp-spacing-8x) 0;
  text-align: center;
}

.Success__checkmark {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: var(--nxp-color-success);
  display: flex;
  align-items: center;
  justify-content: center;
}

.Success__checkmark svg {
  width: 32px;
  height: 32px;
  stroke: #FFFFFF;
  stroke-width: 3;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-dasharray: 32;
  stroke-dashoffset: 32;
  animation: nxp-check-draw 600ms linear 200ms forwards;
}

.Success__title {
  font-size: var(--nxp-font-size-lg);
  font-weight: var(--nxp-font-weight-bold);
  color: var(--nxp-color-text);
}

.Success__message {
  font-size: var(--nxp-font-size-base);
  color: var(--nxp-color-text-secondary);
}
```

### Failure State

```css
.Failure {
  text-align: center;
  padding: var(--nxp-spacing-6x) 0;
}

.Failure__message {
  color: var(--nxp-color-danger);
  font-size: var(--nxp-font-size-base);
  margin-bottom: var(--nxp-spacing-4x);
}

.Failure__retry {
  /* Ghost button style */
  background: transparent;
  border: 1px solid var(--nxp-color-primary);
  color: var(--nxp-color-primary);
  border-radius: var(--nxp-border-radius);
  padding: var(--nxp-spacing-3x) var(--nxp-spacing-6x);
  font-size: var(--nxp-font-size-base);
  font-weight: var(--nxp-font-weight-bold);
  cursor: pointer;
  height: 44px;
  transition: background-color 150ms ease, color 150ms ease;
}

.Failure__retry:hover {
  background: rgba(var(--nxp-color-primary-rgb), 0.04);
}
```

### Performance Target

- **Page load (Time to Interactive):** < 1 second on a simulated 3G connection (1.6 Mbps down, 750ms RTL).
- Total JS bundle: < 40 KB gzipped.
- Total CSS: < 8 KB gzipped.
- No external font requests (system-ui stack).
- Iframe lazy-loaded after host shell paints.
- SVG icons inlined, not fetched.

---

## 12. BIN Database

The SDK uses a client-side BIN (Bank Identification Number) lookup table to detect the card network from the first digits typed. This enables instant brand icon display and correct number formatting.

### Network BIN Ranges

| Network | BIN Prefix(es) | Card Length | CVC Length | Grouping |
|---|---|---|---|---|
| Visa | `4` | 16 (some 13, 19) | 3 | 4-4-4-4 |
| Mastercard | `51`-`55`, `2221`-`2720` | 16 | 3 | 4-4-4-4 |
| Amex | `34`, `37` | 15 | 4 | 4-6-5 |
| Discover | `6011`, `644`-`649`, `65` | 16-19 | 3 | 4-4-4-4 |
| JCB | `3528`-`3589` | 16-19 | 3 | 4-4-4-4 |
| Diners Club | `300`-`305`, `36`, `38` | 14-19 | 3 | 4-6-4 (14-digit), 4-4-4-4 (16+) |
| UnionPay | `62` | 16-19 | 3 | 4-4-4-4 |
| Maestro | `5018`, `5020`, `5038`, `6304`, `6759`, `6761`-`6763` | 12-19 | 3 | 4-4-4-4 |

### Detection Algorithm

```typescript
type CardBrand =
  | 'visa'
  | 'mastercard'
  | 'amex'
  | 'discover'
  | 'jcb'
  | 'diners'
  | 'unionpay'
  | 'maestro'
  | 'unknown';

interface BINRule {
  brand: CardBrand;
  prefixes: Array<[number, number] | number>;  // ranges or exact prefixes
  lengths: number[];
  cvcLength: 3 | 4;
  grouping: number[];
}

const BIN_RULES: BINRule[] = [
  {
    brand: 'amex',
    prefixes: [34, 37],
    lengths: [15],
    cvcLength: 4,
    grouping: [4, 6, 5],
  },
  {
    brand: 'diners',
    prefixes: [[300, 305], 36, 38],
    lengths: [14, 15, 16, 17, 18, 19],
    cvcLength: 3,
    grouping: [4, 6, 4],  // 14-digit; 16+ uses 4-4-4-4
  },
  {
    brand: 'discover',
    prefixes: [6011, [644, 649], 65],
    lengths: [16, 17, 18, 19],
    cvcLength: 3,
    grouping: [4, 4, 4, 4],
  },
  {
    brand: 'jcb',
    prefixes: [[3528, 3589]],
    lengths: [16, 17, 18, 19],
    cvcLength: 3,
    grouping: [4, 4, 4, 4],
  },
  {
    brand: 'unionpay',
    prefixes: [62],
    lengths: [16, 17, 18, 19],
    cvcLength: 3,
    grouping: [4, 4, 4, 4],
  },
  {
    brand: 'mastercard',
    prefixes: [[51, 55], [2221, 2720]],
    lengths: [16],
    cvcLength: 3,
    grouping: [4, 4, 4, 4],
  },
  {
    brand: 'maestro',
    prefixes: [5018, 5020, 5038, 6304, 6759, [6761, 6763]],
    lengths: [12, 13, 14, 15, 16, 17, 18, 19],
    cvcLength: 3,
    grouping: [4, 4, 4, 4],
  },
  {
    brand: 'visa',
    prefixes: [4],
    lengths: [13, 16, 19],
    cvcLength: 3,
    grouping: [4, 4, 4, 4],
  },
];
```

**Matching order matters.** Rules are evaluated top-to-bottom; the first match wins. Amex and Diners are checked before Visa/Mastercard to ensure longer prefixes take precedence over shorter ones (e.g., `34` matches Amex, not the generic `3` range).

When the input is too short to determine a brand (e.g., a single `3` could be Amex, JCB, or Diners), the SDK displays the generic card icon and applies the default 4-4-4-4 grouping. The brand resolves as more digits are entered.

### Luhn Validation

The SDK validates the full card number using the Luhn algorithm on blur and before form submission. If the checksum fails, an inline error is shown: "Your card number is invalid."

---

## Appendix A: Accessibility

- All interactive elements have visible focus indicators (3px ring).
- Color is never the sole indicator of state; icons and text accompany color changes.
- Error messages are linked to inputs via `aria-describedby`.
- Minimum touch target: 44x44px on mobile (WCAG 2.5.5).
- `aria-live="polite"` on error message containers for screen reader announcements.
- All form inputs have associated `<label>` elements.
- Button loading state sets `aria-busy="true"` and `aria-disabled="true"`.

## Appendix B: CSS Reset (card-frame.html)

```css
*, *::before, *::after {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html {
  font-size: 16px;
  -webkit-text-size-adjust: 100%;
}

body {
  font-family: var(--nxp-font-family);
  color: var(--nxp-color-text);
  background: transparent;
  line-height: 1.5;
}

input {
  font: inherit;
  color: inherit;
  background: none;
  border: none;
  outline: none;
  -webkit-appearance: none;
  -moz-appearance: none;
  appearance: none;
}

input::placeholder {
  color: var(--nxp-color-text-secondary);
}
```
