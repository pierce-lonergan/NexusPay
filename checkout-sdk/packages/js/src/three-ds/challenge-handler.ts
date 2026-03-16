/**
 * 3DS Challenge Handler — handles `next_action` from payment confirmation.
 * Supports redirect mode (full page) or iframe mode (3DS in modal).
 * Listens for completion via postMessage / URL callback.
 */

export interface ThreeDSResult {
  status: 'succeeded' | 'failed' | 'cancelled';
  error?: string;
}

export interface NextAction {
  type: 'redirect' | 'three_d_secure';
  url?: string;
}

export class ChallengeHandler {
  private iframe: HTMLIFrameElement | null = null;
  private overlay: HTMLElement | null = null;
  private messageHandler: ((event: MessageEvent) => void) | null = null;
  private resolver: ((result: ThreeDSResult) => void) | null = null;
  private timeout: ReturnType<typeof setTimeout> | null = null;

  /**
   * Handles a 3DS challenge based on the next_action from the server.
   * Returns a promise that resolves when the challenge is completed.
   */
  async handle(nextAction: NextAction): Promise<ThreeDSResult> {
    if (!nextAction.url) {
      return { status: 'failed', error: 'No challenge URL provided' };
    }

    switch (nextAction.type) {
      case 'redirect':
        return this.handleRedirect(nextAction.url);
      case 'three_d_secure':
        return this.handleIframe(nextAction.url);
      default:
        return { status: 'failed', error: `Unknown action type: ${nextAction.type}` };
    }
  }

  /** Cancels an in-progress challenge. */
  cancel(): void {
    this.cleanup();
    if (this.resolver) {
      this.resolver({ status: 'cancelled' });
      this.resolver = null;
    }
  }

  /**
   * Redirect mode — navigates the full page to the 3DS URL.
   * The merchant return URL will receive the result.
   */
  private handleRedirect(url: string): Promise<ThreeDSResult> {
    // In redirect mode, we navigate away. The result will come back
    // via the return URL. We return a promise that never resolves
    // because the page will unload.
    window.location.href = url;
    return new Promise(() => {
      // Page will navigate away — this promise is intentionally unresolved
    });
  }

  /**
   * Iframe mode — opens the 3DS challenge in a modal iframe overlay.
   * Listens for completion via postMessage from the iframe.
   */
  private handleIframe(url: string): Promise<ThreeDSResult> {
    return new Promise((resolve) => {
      this.resolver = resolve;

      // Create overlay
      this.overlay = document.createElement('div');
      Object.assign(this.overlay.style, {
        position: 'fixed',
        top: '0',
        left: '0',
        width: '100%',
        height: '100%',
        backgroundColor: 'rgba(0, 0, 0, 0.5)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: '999999',
      });

      // Close button
      const closeBtn = document.createElement('button');
      Object.assign(closeBtn.style, {
        position: 'absolute',
        top: '16px',
        right: '16px',
        background: 'rgba(255,255,255,0.9)',
        border: 'none',
        borderRadius: '50%',
        width: '32px',
        height: '32px',
        cursor: 'pointer',
        fontSize: '18px',
        lineHeight: '1',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      });
      closeBtn.textContent = '\u00D7';
      closeBtn.setAttribute('aria-label', 'Cancel verification');
      closeBtn.addEventListener('click', () => this.cancel());
      this.overlay.appendChild(closeBtn);

      // Iframe container
      const container = document.createElement('div');
      Object.assign(container.style, {
        width: '400px',
        maxWidth: '95vw',
        height: '600px',
        maxHeight: '90vh',
        backgroundColor: '#FFFFFF',
        borderRadius: '12px',
        overflow: 'hidden',
        boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.25)',
      });

      this.iframe = document.createElement('iframe');
      this.iframe.src = url;
      this.iframe.setAttribute('title', '3D Secure verification');
      Object.assign(this.iframe.style, {
        width: '100%',
        height: '100%',
        border: 'none',
      });

      container.appendChild(this.iframe);
      this.overlay.appendChild(container);
      document.body.appendChild(this.overlay);

      // Listen for completion message from iframe
      this.messageHandler = (event: MessageEvent) => {
        const data = event.data;
        if (!data || typeof data !== 'object') return;

        // NexusPay 3DS completion signal
        if (data.source === 'nexuspay-3ds' && data.type === 'CHALLENGE_COMPLETE') {
          const result: ThreeDSResult = {
            status: data.payload?.status === 'succeeded' ? 'succeeded' : 'failed',
            error: data.payload?.error,
          };
          this.cleanup();
          this.resolver = null;
          resolve(result);
        }
      };

      window.addEventListener('message', this.messageHandler);

      // Timeout after 10 minutes
      this.timeout = setTimeout(() => {
        this.cleanup();
        this.resolver = null;
        resolve({ status: 'failed', error: '3DS verification timed out' });
      }, 10 * 60 * 1000);
    });
  }

  private cleanup(): void {
    if (this.timeout) {
      clearTimeout(this.timeout);
      this.timeout = null;
    }
    if (this.messageHandler) {
      window.removeEventListener('message', this.messageHandler);
      this.messageHandler = null;
    }
    if (this.overlay?.parentElement) {
      this.overlay.parentElement.removeChild(this.overlay);
    }
    this.overlay = null;
    this.iframe = null;
  }
}
