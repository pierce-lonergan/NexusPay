// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { IframeManager, type IframeManagerOptions } from '../elements/iframe-manager';

// Mock DOM environment
function createMockContainer(): HTMLElement {
  const container = document.createElement('div');
  document.body.appendChild(container);
  return container;
}

describe('IframeManager', () => {
  let container: HTMLElement;
  let options: IframeManagerOptions;

  beforeEach(() => {
    container = createMockContainer();
    options = {
      apiBase: 'https://api.test.com',
      sessionToken: 'tok_test_123',
      onReady: vi.fn(),
      onChange: vi.fn(),
      onComplete: vi.fn(),
      onError: vi.fn(),
      onTokenizeResponse: vi.fn(),
    };
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('creates an iframe with correct attributes', async () => {
    const manager = new IframeManager(options);

    // Start creation (won't resolve without FRAME_READY message)
    const createPromise = manager.create(container);

    // Simulate FRAME_READY
    const event = new MessageEvent('message', {
      data: { source: 'nexuspay-card-frame', type: 'FRAME_READY' },
    });
    window.dispatchEvent(event);

    await createPromise;

    const iframe = container.querySelector('iframe');
    expect(iframe).not.toBeNull();
    expect(iframe?.getAttribute('sandbox')).toBe('allow-scripts allow-same-origin allow-forms');
    expect(iframe?.getAttribute('title')).toBe('Secure card input');
    expect(iframe?.src).toContain('/elements/card-frame.html');
    expect(manager.isReady()).toBe(true);
    expect(options.onReady).toHaveBeenCalled();

    manager.destroy();
  });

  it('handles CARD_CHANGE messages from iframe', async () => {
    const manager = new IframeManager(options);
    const createPromise = manager.create(container);

    // Ready
    window.dispatchEvent(new MessageEvent('message', {
      data: { source: 'nexuspay-card-frame', type: 'FRAME_READY' },
    }));
    await createPromise;

    // Card change
    const payload = { complete: false, empty: false, brand: 'visa', error: null, cardLastFour: '4242' };
    window.dispatchEvent(new MessageEvent('message', {
      data: { source: 'nexuspay-card-frame', type: 'CARD_CHANGE', payload },
    }));

    expect(options.onChange).toHaveBeenCalledWith(payload);

    manager.destroy();
  });

  it('handles CARD_COMPLETE messages', async () => {
    const manager = new IframeManager(options);
    const createPromise = manager.create(container);

    window.dispatchEvent(new MessageEvent('message', {
      data: { source: 'nexuspay-card-frame', type: 'FRAME_READY' },
    }));
    await createPromise;

    window.dispatchEvent(new MessageEvent('message', {
      data: { source: 'nexuspay-card-frame', type: 'CARD_COMPLETE' },
    }));

    expect(options.onComplete).toHaveBeenCalled();

    manager.destroy();
  });

  it('handles TOKENIZE_RESPONSE messages', async () => {
    const manager = new IframeManager(options);
    const createPromise = manager.create(container);

    window.dispatchEvent(new MessageEvent('message', {
      data: { source: 'nexuspay-card-frame', type: 'FRAME_READY' },
    }));
    await createPromise;

    const payload = { success: true, tokenId: 'ptok_123' };
    window.dispatchEvent(new MessageEvent('message', {
      data: { source: 'nexuspay-card-frame', type: 'TOKENIZE_RESPONSE', payload },
    }));

    expect(options.onTokenizeResponse).toHaveBeenCalledWith(payload);

    manager.destroy();
  });

  it('ignores messages from unknown sources', async () => {
    const manager = new IframeManager(options);
    const createPromise = manager.create(container);

    // This should be ignored
    window.dispatchEvent(new MessageEvent('message', {
      data: { source: 'unknown-source', type: 'FRAME_READY' },
    }));

    // Should still be waiting (not ready)
    expect(manager.isReady()).toBe(false);

    // Send proper ready message
    window.dispatchEvent(new MessageEvent('message', {
      data: { source: 'nexuspay-card-frame', type: 'FRAME_READY' },
    }));
    await createPromise;

    expect(manager.isReady()).toBe(true);
    manager.destroy();
  });

  it('calls onError when requestTokenize is called before ready', () => {
    const manager = new IframeManager(options);
    manager.requestTokenize();
    expect(options.onError).toHaveBeenCalledWith('Card frame is not ready');
  });

  it('cleans up on destroy', async () => {
    const manager = new IframeManager(options);
    const createPromise = manager.create(container);

    window.dispatchEvent(new MessageEvent('message', {
      data: { source: 'nexuspay-card-frame', type: 'FRAME_READY' },
    }));
    await createPromise;

    expect(container.querySelector('iframe')).not.toBeNull();
    manager.destroy();
    expect(container.querySelector('iframe')).toBeNull();
    expect(manager.isReady()).toBe(false);
  });
});
