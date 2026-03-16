/**
 * IframeManager — creates and manages a sandboxed iframe for PCI-compliant card input.
 * The iframe isolates card data so the PAN never crosses the postMessage boundary;
 * only tokenization results are communicated back.
 *
 * PostMessage protocol:
 *   Parent → Iframe: STYLE_UPDATE, TOKENIZE_REQUEST, FOCUS_REQUEST
 *   Iframe → Parent: CARD_CHANGE, CARD_COMPLETE, CARD_ERROR, TOKENIZE_RESPONSE, FRAME_READY
 */

import type { Appearance } from '../types';

export interface CardChangePayload {
  complete: boolean;
  empty: boolean;
  brand: string;
  error: string | null;
  cardLastFour: string | null;
}

export interface TokenizeResponsePayload {
  success: boolean;
  tokenId?: string;
  error?: string;
}

export type IframeMessageType =
  | 'FRAME_READY'
  | 'CARD_CHANGE'
  | 'CARD_COMPLETE'
  | 'CARD_ERROR'
  | 'TOKENIZE_RESPONSE'
  | 'STYLE_UPDATE'
  | 'TOKENIZE_REQUEST'
  | 'FOCUS_REQUEST';

export interface IframeMessage {
  source: 'nexuspay-card-frame' | 'nexuspay-parent';
  type: IframeMessageType;
  payload?: unknown;
}

export interface IframeManagerOptions {
  apiBase: string;
  sessionToken: string;
  appearance?: Appearance;
  onReady?: () => void;
  onChange?: (payload: CardChangePayload) => void;
  onComplete?: () => void;
  onError?: (error: string) => void;
  onTokenizeResponse?: (payload: TokenizeResponsePayload) => void;
}

export class IframeManager {
  private iframe: HTMLIFrameElement | null = null;
  private readonly options: IframeManagerOptions;
  private messageHandler: ((event: MessageEvent) => void) | null = null;
  private ready = false;

  constructor(options: IframeManagerOptions) {
    this.options = options;
  }

  /**
   * Creates the sandboxed iframe and appends it to the container.
   * Returns a promise that resolves when the iframe signals FRAME_READY.
   */
  create(container: HTMLElement): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this.iframe = document.createElement('iframe');
      this.iframe.setAttribute('title', 'Secure card input');
      this.iframe.setAttribute(
        'sandbox',
        'allow-scripts allow-same-origin allow-forms',
      );
      this.iframe.setAttribute('frameborder', '0');
      this.iframe.setAttribute('scrolling', 'no');
      this.iframe.setAttribute('allowtransparency', 'true');

      // Styles
      Object.assign(this.iframe.style, {
        width: '100%',
        height: '100%',
        minHeight: '44px',
        border: 'none',
        display: 'block',
        colorScheme: 'auto',
      });

      // Set iframe src to card-frame.html on NexusPay CDN
      const frameUrl = new URL('/elements/card-frame.html', this.options.apiBase);
      frameUrl.searchParams.set('v', '1');
      this.iframe.src = frameUrl.toString();

      // Listen for messages from iframe
      const timeout = setTimeout(() => {
        reject(new Error('Card frame did not load within 10 seconds'));
      }, 10_000);

      this.messageHandler = (event: MessageEvent) => {
        if (!this.isValidMessage(event)) return;

        const msg = event.data as IframeMessage;

        switch (msg.type) {
          case 'FRAME_READY':
            clearTimeout(timeout);
            this.ready = true;
            // Send initial styles
            this.sendStyleUpdate();
            // Send session token
            this.postMessage({
              source: 'nexuspay-parent',
              type: 'STYLE_UPDATE',
              payload: {
                sessionToken: this.options.sessionToken,
                apiBase: this.options.apiBase,
              },
            });
            this.options.onReady?.();
            resolve();
            break;

          case 'CARD_CHANGE':
            this.options.onChange?.(msg.payload as CardChangePayload);
            break;

          case 'CARD_COMPLETE':
            this.options.onComplete?.();
            break;

          case 'CARD_ERROR':
            this.options.onError?.(
              (msg.payload as { message: string })?.message ?? 'Unknown error',
            );
            break;

          case 'TOKENIZE_RESPONSE':
            this.options.onTokenizeResponse?.(
              msg.payload as TokenizeResponsePayload,
            );
            break;
        }
      };

      window.addEventListener('message', this.messageHandler);
      container.appendChild(this.iframe);
    });
  }

  /** Sends a tokenization request to the iframe. */
  requestTokenize(): void {
    if (!this.ready) {
      this.options.onError?.('Card frame is not ready');
      return;
    }
    this.postMessage({
      source: 'nexuspay-parent',
      type: 'TOKENIZE_REQUEST',
    });
  }

  /** Requests the iframe to focus the card number field. */
  focus(): void {
    this.postMessage({
      source: 'nexuspay-parent',
      type: 'FOCUS_REQUEST',
    });
  }

  /** Updates iframe styles (e.g., after appearance change). */
  updateAppearance(appearance: Appearance): void {
    this.options.appearance = appearance;
    if (this.ready) {
      this.sendStyleUpdate();
    }
  }

  /** Removes the iframe and cleans up listeners. */
  destroy(): void {
    if (this.messageHandler) {
      window.removeEventListener('message', this.messageHandler);
      this.messageHandler = null;
    }
    if (this.iframe?.parentElement) {
      this.iframe.parentElement.removeChild(this.iframe);
    }
    this.iframe = null;
    this.ready = false;
  }

  isReady(): boolean {
    return this.ready;
  }

  private sendStyleUpdate(): void {
    this.postMessage({
      source: 'nexuspay-parent',
      type: 'STYLE_UPDATE',
      payload: {
        appearance: this.options.appearance,
      },
    });
  }

  private postMessage(msg: IframeMessage): void {
    if (!this.iframe?.contentWindow) return;
    this.iframe.contentWindow.postMessage(msg, '*');
  }

  private isValidMessage(event: MessageEvent): boolean {
    const data = event.data;
    return (
      data &&
      typeof data === 'object' &&
      data.source === 'nexuspay-card-frame' &&
      typeof data.type === 'string'
    );
  }
}
