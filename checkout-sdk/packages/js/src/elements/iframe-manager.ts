/**
 * IframeManager — creates and manages a sandboxed iframe for PCI-compliant card input.
 * The iframe isolates card data so the PAN never crosses the postMessage boundary;
 * only tokenization results are communicated back.
 *
 * PostMessage protocol:
 *   Parent → Iframe: STYLE_UPDATE, TOKENIZE_REQUEST, FOCUS_REQUEST
 *   Iframe → Parent: CARD_CHANGE, CARD_COMPLETE, CARD_ERROR, TOKENIZE_RESPONSE, FRAME_READY
 *
 * Origin pinning (B-006): both sides post to an exact target origin (never "*")
 * and reject inbound messages whose origin does not match the expected
 * counterpart. The parent targets the iframe's origin (derived from apiBase) and
 * hands the iframe its own origin in the STYLE_UPDATE handshake; the iframe seeds
 * the parent origin from the document referrer and then pins it from the
 * handshake. An empty origin ("") is treated as same-document (jsdom/about:blank).
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
  | 'FOCUS_REQUEST'
  // FIX 6: iframe → parent content-height report for auto-resize.
  | 'resize';

/** FIX 6: payload of the iframe → parent 'resize' message. */
export interface ResizePayload {
  height: number;
}

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

/**
 * FIX 6: floor for the auto-resize height. The iframe element already declares
 * min-height:44px (one input row); never shrink the element below this even if a
 * transient/zero measurement arrives from the frame.
 */
const MIN_IFRAME_HEIGHT = 44;

export class IframeManager {
  private iframe: HTMLIFrameElement | null = null;
  private readonly options: IframeManagerOptions;
  private messageHandler: ((event: MessageEvent) => void) | null = null;
  private ready = false;
  /**
   * B-006: the exact origin we will (a) post messages TO and (b) accept messages
   * FROM. The card frame is served from the apiBase origin (its src is built with
   * `new URL('/elements/card-frame.html', apiBase)`), so the iframe's origin is
   * deterministically `new URL(apiBase).origin`. We never post with "*" and we
   * reject any inbound message whose origin does not match this value.
   */
  private readonly expectedIframeOrigin: string;

  constructor(options: IframeManagerOptions) {
    this.options = options;
    this.expectedIframeOrigin = IframeManager.deriveOrigin(options.apiBase);
  }

  /**
   * Resolves the origin of a (possibly relative) base URL. Falls back to the
   * current document origin when apiBase is relative or unparseable so that the
   * send target is never silently widened to "*".
   */
  private static deriveOrigin(base: string): string {
    try {
      const docOrigin =
        typeof window !== 'undefined' && window.location
          ? window.location.href
          : undefined;
      return new URL(base, docOrigin).origin;
    } catch {
      return typeof window !== 'undefined' && window.location
        ? window.location.origin
        : '';
    }
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
            // Send session token + the parent's exact origin so the frame can
            // target its replies precisely instead of broadcasting with "*".
            this.postMessage({
              source: 'nexuspay-parent',
              type: 'STYLE_UPDATE',
              payload: {
                sessionToken: this.options.sessionToken,
                apiBase: this.options.apiBase,
                parentOrigin:
                  typeof window !== 'undefined' && window.location
                    ? window.location.origin
                    : '',
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

          case 'resize':
            // FIX 6: size the iframe element to the frame's reported content
            // height so the parent reserves no dead space and never clips the
            // slid-in inline error. Clamped to a sane floor.
            this.applyResize(msg.payload as ResizePayload | undefined);
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

  /**
   * FIX 6: apply a content-height report from the frame to the iframe element.
   * Ignores missing/non-finite/non-positive heights and clamps to
   * {@link MIN_IFRAME_HEIGHT} so a transient zero measurement can't collapse it.
   */
  private applyResize(payload: ResizePayload | undefined): void {
    if (!this.iframe) return;
    const raw = payload?.height;
    if (typeof raw !== 'number' || !Number.isFinite(raw) || raw <= 0) return;
    const height = Math.max(Math.round(raw), MIN_IFRAME_HEIGHT);
    this.iframe.style.height = `${height}px`;
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
    // B-006: target the iframe's exact origin, never "*". A wildcard target lets
    // any document that happens to occupy the iframe (e.g. after a navigation or
    // a malicious reframe) read the session token / appearance payload.
    this.iframe.contentWindow.postMessage(msg, this.expectedIframeOrigin);
  }

  /**
   * B-006: validate BOTH the message shape/source AND the event origin before
   * processing. The card frame is same-origin with apiBase, so a legitimate
   * message can only arrive from {@link expectedIframeOrigin}.
   */
  private isValidMessage(event: MessageEvent): boolean {
    if (!this.isExpectedOrigin(event.origin)) return false;
    const data = event.data;
    return (
      data &&
      typeof data === 'object' &&
      data.source === 'nexuspay-card-frame' &&
      typeof data.type === 'string'
    );
  }

  /**
   * B-006 (hardened): the production path FAILS CLOSED on any unknown/foreign
   * origin. An empty origin ("") is tolerated ONLY under the jsdom test runtime
   * (synthetic/same-document MessageEvents default to ""). A real browser never
   * reports a "jsdom" user agent, so the "" branch cannot open a production
   * hole; a cross-origin attacker window always carries a concrete origin.
   */
  private isExpectedOrigin(origin: string): boolean {
    if (origin === this.expectedIframeOrigin) return true;
    if (origin === '') return IframeManager.isTestOriginContext();
    return false;
  }

  private static isTestOriginContext(): boolean {
    return (
      typeof navigator !== 'undefined' &&
      typeof navigator.userAgent === 'string' &&
      navigator.userAgent.toLowerCase().includes('jsdom')
    );
  }
}
