/**
 * CardElement — PCI-compliant card input rendered inside a sandboxed iframe.
 * Handles brand detection icon crossfade, number formatting with cursor
 * preservation, expiry auto-advance, and CVC 3/4-digit per brand.
 */

import { BaseElement, type BaseElementEvents } from './base-element';
import {
  IframeManager,
  type CardChangePayload,
  type TokenizeResponsePayload,
} from './iframe-manager';
import { CARD_BRAND_ICONS } from './icons';
import type { Appearance, ElementOptions, CardBrand } from '../types';

export interface CardElementEvents extends BaseElementEvents {
  [key: string]: unknown;
  change: CardChangePayload;
  complete: void;
}

export interface CardElementOptions extends ElementOptions {
  apiBase?: string;
  sessionToken?: string;
}

export class CardElement extends BaseElement<CardElementEvents> {
  private iframeManager: IframeManager | null = null;
  private brandIconEl: HTMLElement | null = null;
  private currentBrand: CardBrand = 'unknown';
  private readonly apiBase: string;
  private sessionToken: string;
  private tokenizeResolver: ((value: TokenizeResponsePayload) => void) | null = null;

  constructor(options?: CardElementOptions) {
    super(options);
    this.apiBase = options?.apiBase ?? 'https://api.nexuspay.io';
    this.sessionToken = options?.sessionToken ?? '';
  }

  protected elementType(): string {
    return 'card';
  }

  protected async render(): Promise<void> {
    if (!this.wrapper) return;

    // Card input container
    const inputContainer = document.createElement('div');
    inputContainer.style.position = 'relative';
    inputContainer.style.width = '100%';
    inputContainer.style.minHeight = '44px';

    // Brand icon overlay
    this.brandIconEl = document.createElement('div');
    this.brandIconEl.className = 'BrandIcon';
    this.brandIconEl.innerHTML = CARD_BRAND_ICONS.unknown;
    this.brandIconEl.setAttribute('aria-hidden', 'true');
    inputContainer.appendChild(this.brandIconEl);

    // Iframe for card input
    this.iframeManager = new IframeManager({
      apiBase: this.apiBase,
      sessionToken: this.sessionToken,
      appearance: this.appearance,
      onReady: () => {
        // Iframe loaded successfully
      },
      onChange: (payload) => {
        this.updateBrandIcon(payload.brand as CardBrand);
        this.emit('change', payload);
        if (payload.complete) {
          this.emit('complete', undefined);
        }
      },
      onComplete: () => {
        this.emit('complete', undefined);
      },
      onError: (message) => {
        this.emit('error', { message });
      },
      onTokenizeResponse: (payload) => {
        if (this.tokenizeResolver) {
          this.tokenizeResolver(payload);
          this.tokenizeResolver = null;
        }
      },
    });

    this.wrapper.appendChild(inputContainer);
    await this.iframeManager.create(inputContainer);
  }

  protected cleanup(): void {
    this.iframeManager?.destroy();
    this.iframeManager = null;
    this.brandIconEl = null;
    this.tokenizeResolver = null;
  }

  /** Updates the element appearance. */
  override update(options: ElementOptions): void {
    super.update(options);
    if (options.appearance && this.iframeManager) {
      this.iframeManager.updateAppearance(options.appearance as Appearance);
    }
  }

  /** Sets the session token (used when session is loaded after element creation). */
  setSessionToken(token: string): void {
    this.sessionToken = token;
  }

  /** Focuses the card number field inside the iframe. */
  focusInput(): void {
    this.iframeManager?.focus();
  }

  /**
   * Requests tokenization of the entered card data.
   * Returns a promise that resolves with the tokenization result from the iframe.
   */
  requestTokenize(): Promise<TokenizeResponsePayload> {
    return new Promise((resolve, reject) => {
      if (!this.iframeManager?.isReady()) {
        reject(new Error('Card element is not ready'));
        return;
      }

      this.tokenizeResolver = resolve;

      // Timeout after 30 seconds
      const timeout = setTimeout(() => {
        this.tokenizeResolver = null;
        reject(new Error('Tokenization timed out'));
      }, 30_000);

      // Override resolver to clear timeout
      const originalResolver = this.tokenizeResolver;
      this.tokenizeResolver = (payload) => {
        clearTimeout(timeout);
        originalResolver(payload);
      };

      this.iframeManager.requestTokenize();
    });
  }

  /** Crossfade the brand icon when card brand changes. */
  private updateBrandIcon(brand: CardBrand): void {
    if (brand === this.currentBrand || !this.brandIconEl) return;

    // Crossfade: hide current, swap, show new
    this.brandIconEl.classList.add('BrandIcon--hidden');

    setTimeout(() => {
      if (!this.brandIconEl) return;
      this.brandIconEl.innerHTML =
        CARD_BRAND_ICONS[brand] ?? CARD_BRAND_ICONS.unknown;
      this.brandIconEl.classList.remove('BrandIcon--hidden');
    }, 150); // matches ANIMATION.brandCrossfade

    this.currentBrand = brand;
  }
}
