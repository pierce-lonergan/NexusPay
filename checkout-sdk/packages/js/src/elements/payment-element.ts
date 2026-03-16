/**
 * PaymentElement — composite element with payment method tabs.
 * Card tab delegates to CardElement (PCI iframe).
 * Desktop: horizontal tabs. Mobile (<640px): radio-button list.
 * Tab switch uses 200ms crossfade animation.
 */

import { BaseElement, type BaseElementEvents } from './base-element';
import { CardElement, type CardElementOptions } from './card-element';
import type { PaymentMethodType, ElementOptions } from '../types';
import {
  ICON_VISA,
  ICON_MASTERCARD,
  ICON_APPLE_PAY,
  ICON_GOOGLE_PAY,
  ICON_BANK,
  ICON_BNPL,
} from './icons';

export interface PaymentElementEvents extends BaseElementEvents {
  [key: string]: unknown;
  change: { paymentMethod: PaymentMethodType; complete: boolean };
}

export interface PaymentElementOptions extends ElementOptions {
  apiBase?: string;
  sessionToken?: string;
  allowedMethods?: PaymentMethodType[];
}

interface TabConfig {
  type: PaymentMethodType;
  label: string;
  icon: string;
}

const DEFAULT_TABS: TabConfig[] = [
  { type: 'card', label: 'Card', icon: `${ICON_VISA}${ICON_MASTERCARD}` },
  { type: 'apple_pay', label: 'Apple Pay', icon: ICON_APPLE_PAY },
  { type: 'google_pay', label: 'Google Pay', icon: ICON_GOOGLE_PAY },
  { type: 'bank_redirect', label: 'Bank', icon: ICON_BANK },
  { type: 'bnpl', label: 'Buy Now, Pay Later', icon: ICON_BNPL },
];

export class PaymentElement extends BaseElement<PaymentElementEvents> {
  private cardElement: CardElement | null = null;
  private selectedMethod: PaymentMethodType = 'card';
  private allowedMethods: PaymentMethodType[];
  private tabListEl: HTMLElement | null = null;
  private contentEl: HTMLElement | null = null;
  private readonly apiBase: string;
  private sessionToken: string;

  constructor(options?: PaymentElementOptions) {
    super(options);
    this.allowedMethods = options?.allowedMethods ?? ['card'];
    this.apiBase = options?.apiBase ?? 'https://api.nexuspay.io';
    this.sessionToken = options?.sessionToken ?? '';
  }

  protected elementType(): string {
    return 'payment';
  }

  protected async render(): Promise<void> {
    if (!this.wrapper) return;

    const root = document.createElement('div');
    root.className = 'PaymentElement';

    // Only show tabs if multiple methods allowed
    if (this.allowedMethods.length > 1) {
      this.tabListEl = this.createTabs();
      root.appendChild(this.tabListEl);
    }

    // Content area
    this.contentEl = document.createElement('div');
    this.contentEl.className = 'PaymentElement__content';
    this.contentEl.style.marginTop = '12px';
    root.appendChild(this.contentEl);

    this.wrapper.appendChild(root);

    // Render initial tab content
    await this.renderTabContent(this.selectedMethod);
  }

  protected cleanup(): void {
    this.cardElement?.destroy();
    this.cardElement = null;
    this.tabListEl = null;
    this.contentEl = null;
  }

  /** Returns the currently selected payment method and whether it's complete. */
  getValue(): { paymentMethod: PaymentMethodType } {
    return { paymentMethod: this.selectedMethod };
  }

  /** Gets the embedded CardElement (for tokenization). */
  getCardElement(): CardElement | null {
    return this.cardElement;
  }

  setSessionToken(token: string): void {
    this.sessionToken = token;
    this.cardElement?.setSessionToken(token);
  }

  private createTabs(): HTMLElement {
    const tabList = document.createElement('div');
    tabList.className = 'TabList';
    tabList.setAttribute('role', 'tablist');
    tabList.style.display = 'flex';
    tabList.style.gap = '0';
    tabList.style.borderBottom = '1px solid var(--nxp-border-default, #E5E7EB)';

    const tabs = DEFAULT_TABS.filter((t) =>
      this.allowedMethods.includes(t.type),
    );

    for (const tab of tabs) {
      const button = document.createElement('button');
      button.className = `Tab${tab.type === this.selectedMethod ? ' Tab--selected' : ''}`;
      button.setAttribute('role', 'tab');
      button.setAttribute('aria-selected', String(tab.type === this.selectedMethod));
      button.setAttribute('data-method', tab.type);
      button.type = 'button';

      // Icon
      const iconSpan = document.createElement('span');
      iconSpan.className = 'Tab__icon';
      iconSpan.innerHTML = tab.icon;
      iconSpan.setAttribute('aria-hidden', 'true');
      button.appendChild(iconSpan);

      // Label
      const labelSpan = document.createElement('span');
      labelSpan.textContent = tab.label;
      button.appendChild(labelSpan);

      button.addEventListener('click', () => this.selectTab(tab.type));
      tabList.appendChild(button);
    }

    return tabList;
  }

  private async selectTab(method: PaymentMethodType): Promise<void> {
    if (method === this.selectedMethod) return;

    // Update tab UI
    if (this.tabListEl) {
      const tabs = this.tabListEl.querySelectorAll('.Tab');
      tabs.forEach((tab) => {
        const el = tab as HTMLElement;
        const isSelected = el.getAttribute('data-method') === method;
        el.classList.toggle('Tab--selected', isSelected);
        el.setAttribute('aria-selected', String(isSelected));
      });
    }

    this.selectedMethod = method;

    // Crossfade content
    if (this.contentEl) {
      this.contentEl.style.opacity = '0';
      this.contentEl.style.transition = 'opacity 200ms ease-in-out';

      await new Promise((r) => setTimeout(r, 200));

      // Clean up previous content
      this.cardElement?.destroy();
      this.cardElement = null;
      this.contentEl.innerHTML = '';

      await this.renderTabContent(method);

      this.contentEl.style.opacity = '1';
    }

    this.emit('change', { paymentMethod: method, complete: false });
  }

  private async renderTabContent(method: PaymentMethodType): Promise<void> {
    if (!this.contentEl) return;

    switch (method) {
      case 'card':
        await this.renderCardContent();
        break;
      case 'apple_pay':
        this.renderAPMPlaceholder('Apple Pay', 'Complete payment with Apple Pay');
        break;
      case 'google_pay':
        this.renderAPMPlaceholder('Google Pay', 'Complete payment with Google Pay');
        break;
      case 'bank_redirect':
        this.renderAPMPlaceholder('Bank Transfer', 'Select your bank to continue');
        break;
      case 'bnpl':
        this.renderAPMPlaceholder('Buy Now, Pay Later', 'Choose a provider to continue');
        break;
    }
  }

  private async renderCardContent(): Promise<void> {
    if (!this.contentEl) return;

    const cardContainer = document.createElement('div');
    cardContainer.setAttribute('data-nexuspay-card-mount', '');
    this.contentEl.appendChild(cardContainer);

    this.cardElement = new CardElement({
      appearance: this.appearance,
      apiBase: this.apiBase,
      sessionToken: this.sessionToken,
    } as CardElementOptions);

    this.cardElement.on('change', (payload) => {
      this.emit('change', {
        paymentMethod: 'card',
        complete: payload.complete,
      });
    });

    this.cardElement.mount(cardContainer);
  }

  private renderAPMPlaceholder(title: string, description: string): void {
    if (!this.contentEl) return;

    const placeholder = document.createElement('div');
    placeholder.style.padding = '24px';
    placeholder.style.textAlign = 'center';
    placeholder.style.color = 'var(--nxp-color-text, #1A1A2E)';
    placeholder.style.opacity = '0.7';

    const heading = document.createElement('div');
    heading.style.fontWeight = 'var(--nxp-font-weight-bold, 600)';
    heading.style.marginBottom = '8px';
    heading.textContent = title;

    const desc = document.createElement('div');
    desc.style.fontSize = '0.875rem';
    desc.textContent = description;

    placeholder.appendChild(heading);
    placeholder.appendChild(desc);
    this.contentEl.appendChild(placeholder);
  }
}
