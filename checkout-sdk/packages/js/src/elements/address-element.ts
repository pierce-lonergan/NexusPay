/**
 * AddressElement — billing/shipping address form.
 * Country-aware formatting with fields: name, line1, line2, city, state, postal_code, country.
 */

import { BaseElement, type BaseElementEvents } from './base-element';
import type { ElementOptions } from '../types';

export interface AddressData {
  name: string;
  line1: string;
  line2: string;
  city: string;
  state: string;
  postalCode: string;
  country: string;
}

export interface AddressElementEvents extends BaseElementEvents {
  [key: string]: unknown;
  change: { value: AddressData; complete: boolean };
}

export interface AddressElementOptions extends ElementOptions {
  mode?: 'billing' | 'shipping';
  defaultValues?: Partial<AddressData>;
  countries?: string[];
}

const DEFAULT_COUNTRIES = [
  'US', 'CA', 'GB', 'DE', 'FR', 'AU', 'JP', 'NL', 'BE', 'AT', 'CH', 'IE',
  'IT', 'ES', 'PT', 'SE', 'NO', 'DK', 'FI', 'PL', 'CZ', 'NZ', 'SG', 'HK',
];

const COUNTRY_NAMES: Record<string, string> = {
  US: 'United States', CA: 'Canada', GB: 'United Kingdom', DE: 'Germany',
  FR: 'France', AU: 'Australia', JP: 'Japan', NL: 'Netherlands',
  BE: 'Belgium', AT: 'Austria', CH: 'Switzerland', IE: 'Ireland',
  IT: 'Italy', ES: 'Spain', PT: 'Portugal', SE: 'Sweden',
  NO: 'Norway', DK: 'Denmark', FI: 'Finland', PL: 'Poland',
  CZ: 'Czech Republic', NZ: 'New Zealand', SG: 'Singapore', HK: 'Hong Kong',
};

/** Countries that use state/province. */
const COUNTRIES_WITH_STATE = new Set(['US', 'CA', 'AU']);

/** Label for postal code by country. */
function postalCodeLabel(country: string): string {
  if (country === 'US') return 'ZIP code';
  return 'Postal code';
}

/** Label for state/province by country. */
function stateLabel(country: string): string {
  if (country === 'US') return 'State';
  if (country === 'CA') return 'Province';
  if (country === 'AU') return 'State/Territory';
  return 'Region';
}

export class AddressElement extends BaseElement<AddressElementEvents> {
  private mode: 'billing' | 'shipping';
  private values: AddressData;
  private countries: string[];
  private formEl: HTMLElement | null = null;

  constructor(options?: AddressElementOptions) {
    super(options);
    this.mode = options?.mode ?? 'billing';
    this.countries = options?.countries ?? DEFAULT_COUNTRIES;
    this.values = {
      name: options?.defaultValues?.name ?? '',
      line1: options?.defaultValues?.line1 ?? '',
      line2: options?.defaultValues?.line2 ?? '',
      city: options?.defaultValues?.city ?? '',
      state: options?.defaultValues?.state ?? '',
      postalCode: options?.defaultValues?.postalCode ?? '',
      country: options?.defaultValues?.country ?? 'US',
    };
  }

  protected elementType(): string {
    return 'address';
  }

  protected async render(): Promise<void> {
    if (!this.wrapper) return;
    this.formEl = document.createElement('div');
    this.formEl.className = 'AddressElement';
    this.buildForm();
    this.wrapper.appendChild(this.formEl);
  }

  protected cleanup(): void {
    this.formEl = null;
  }

  getValue(): AddressData {
    return { ...this.values };
  }

  private buildForm(): void {
    if (!this.formEl) return;
    this.formEl.innerHTML = '';

    const fields: Array<{
      key: keyof AddressData;
      label: string;
      type: 'text' | 'select';
      autocomplete: string;
      placeholder: string;
      fullWidth?: boolean;
      options?: Array<{ value: string; label: string }>;
      hidden?: boolean;
    }> = [
      { key: 'country', label: 'Country', type: 'select', autocomplete: `${this.mode} country`, placeholder: '', fullWidth: true, options: this.countries.map((c) => ({ value: c, label: COUNTRY_NAMES[c] ?? c })) },
      { key: 'name', label: 'Full name', type: 'text', autocomplete: `${this.mode} name`, placeholder: 'Jane Smith', fullWidth: true },
      { key: 'line1', label: 'Address line 1', type: 'text', autocomplete: `${this.mode} address-line1`, placeholder: '123 Main St', fullWidth: true },
      { key: 'line2', label: 'Address line 2', type: 'text', autocomplete: `${this.mode} address-line2`, placeholder: 'Apt, suite, etc. (optional)', fullWidth: true },
      { key: 'city', label: 'City', type: 'text', autocomplete: `${this.mode} address-level2`, placeholder: 'San Francisco' },
      { key: 'state', label: stateLabel(this.values.country), type: 'text', autocomplete: `${this.mode} address-level1`, placeholder: '', hidden: !COUNTRIES_WITH_STATE.has(this.values.country) },
      { key: 'postalCode', label: postalCodeLabel(this.values.country), type: 'text', autocomplete: `${this.mode} postal-code`, placeholder: '' },
    ];

    const grid = document.createElement('div');
    grid.style.display = 'grid';
    grid.style.gridTemplateColumns = '1fr 1fr';
    grid.style.gap = '12px';

    for (const field of fields) {
      if (field.hidden) continue;

      const wrapper = document.createElement('div');
      if (field.fullWidth) wrapper.style.gridColumn = '1 / -1';

      const label = document.createElement('label');
      label.className = 'Label';
      label.textContent = field.label;

      let input: HTMLInputElement | HTMLSelectElement;

      if (field.type === 'select' && field.options) {
        input = document.createElement('select');
        input.className = 'Input';
        for (const opt of field.options) {
          const option = document.createElement('option');
          option.value = opt.value;
          option.textContent = opt.label;
          input.appendChild(option);
        }
        input.value = this.values[field.key];
      } else {
        input = document.createElement('input');
        input.className = 'Input';
        input.type = 'text';
        input.placeholder = field.placeholder;
        input.setAttribute('autocomplete', field.autocomplete);
        input.value = this.values[field.key];
      }

      input.addEventListener('input', () => {
        this.values[field.key] = input.value;
        if (field.key === 'country') {
          this.buildForm(); // Rebuild for country-specific fields
        }
        this.emitChange();
      });

      wrapper.appendChild(label);
      wrapper.appendChild(input);
      grid.appendChild(wrapper);
    }

    this.formEl.appendChild(grid);
  }

  private emitChange(): void {
    const complete =
      this.values.name.trim().length > 0 &&
      this.values.line1.trim().length > 0 &&
      this.values.city.trim().length > 0 &&
      this.values.postalCode.trim().length > 0 &&
      this.values.country.length > 0 &&
      (!COUNTRIES_WITH_STATE.has(this.values.country) || this.values.state.trim().length > 0);

    this.emit('change', { value: { ...this.values }, complete });
  }
}
