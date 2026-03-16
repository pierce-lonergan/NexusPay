// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { CardElement } from '../elements/card-element';

describe('CardElement', () => {
  let container: HTMLElement;

  beforeEach(() => {
    container = document.createElement('div');
    container.id = 'card-container';
    document.body.appendChild(container);
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('creates a card element with correct element type', () => {
    const element = new CardElement({
      apiBase: 'https://api.test.com',
      sessionToken: 'tok_test',
    });
    // Access protected method via any
    expect((element as any).elementType()).toBe('card');
    element.destroy();
  });

  it('reports not mounted initially', () => {
    const element = new CardElement();
    expect(element.isMounted()).toBe(false);
    element.destroy();
  });

  it('mounts into a container element', async () => {
    const element = new CardElement({
      apiBase: 'https://api.test.com',
      sessionToken: 'tok_test',
    });

    const readyHandler = vi.fn();
    element.on('ready', readyHandler);

    element.mount(container);
    expect(element.isMounted()).toBe(true);

    // Wrapper should be added
    const wrapper = container.querySelector('[data-nexuspay-element="card"]');
    expect(wrapper).not.toBeNull();

    // Simulate iframe FRAME_READY so element completes rendering
    window.dispatchEvent(new MessageEvent('message', {
      data: { source: 'nexuspay-card-frame', type: 'FRAME_READY' },
    }));

    // Give the async render time to complete
    await new Promise((r) => setTimeout(r, 50));

    element.destroy();
  });

  it('mounts via CSS selector string', () => {
    const element = new CardElement({
      apiBase: 'https://api.test.com',
      sessionToken: 'tok_test',
    });

    element.mount('#card-container');
    expect(element.isMounted()).toBe(true);

    const wrapper = container.querySelector('[data-nexuspay-element="card"]');
    expect(wrapper).not.toBeNull();

    element.destroy();
  });

  it('throws when container is not found', () => {
    const element = new CardElement();
    expect(() => element.mount('#nonexistent')).toThrow('Container not found');
    element.destroy();
  });

  it('warns when mounting twice', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const element = new CardElement({
      apiBase: 'https://api.test.com',
      sessionToken: 'tok_test',
    });

    element.mount(container);
    element.mount(container); // Should warn

    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('already mounted'),
    );

    warnSpy.mockRestore();
    element.destroy();
  });

  it('unmounts and cleans up', () => {
    const element = new CardElement({
      apiBase: 'https://api.test.com',
      sessionToken: 'tok_test',
    });

    element.mount(container);
    expect(element.isMounted()).toBe(true);
    expect(container.children.length).toBeGreaterThan(0);

    element.unmount();
    expect(element.isMounted()).toBe(false);
    expect(container.children.length).toBe(0);

    element.destroy();
  });

  it('emits change events from iframe messages', async () => {
    const element = new CardElement({
      apiBase: 'https://api.test.com',
      sessionToken: 'tok_test',
    });

    const changeHandler = vi.fn();
    element.on('change', changeHandler);

    element.mount(container);

    // Simulate FRAME_READY
    window.dispatchEvent(new MessageEvent('message', {
      data: { source: 'nexuspay-card-frame', type: 'FRAME_READY' },
    }));

    await new Promise((r) => setTimeout(r, 50));

    // Simulate CARD_CHANGE
    const payload = {
      complete: false,
      empty: false,
      brand: 'visa',
      error: null,
      cardLastFour: '4242',
    };
    window.dispatchEvent(new MessageEvent('message', {
      data: { source: 'nexuspay-card-frame', type: 'CARD_CHANGE', payload },
    }));

    expect(changeHandler).toHaveBeenCalledWith(payload);

    element.destroy();
  });

  it('can set session token after creation', () => {
    const element = new CardElement();
    element.setSessionToken('tok_new_123');
    // Should not throw
    expect(() => element.destroy()).not.toThrow();
  });
});
