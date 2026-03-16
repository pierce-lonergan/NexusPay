/**
 * 3DS Method (Device Fingerprint Collection) — collects device fingerprint
 * via a hidden iframe to improve 3DS authentication success rates.
 * The issuer collects browser data without user interaction.
 */

export interface FingerprintResult {
  success: boolean;
  threeDSMethodData?: string;
  error?: string;
}

export class FingerprintHandler {
  private iframe: HTMLIFrameElement | null = null;
  private timeout: ReturnType<typeof setTimeout> | null = null;

  /**
   * Performs 3DS device fingerprinting by loading the issuer's 3DS Method URL
   * in a hidden iframe. The issuer collects browser characteristics.
   *
   * @param threeDSMethodUrl - URL provided by the issuer for fingerprint collection
   * @param threeDSServerTransID - Transaction ID to include in the form POST
   * @param notificationUrl - URL the issuer will POST the result to
   * @param timeoutMs - Maximum time to wait (default: 10 seconds)
   */
  collect(
    threeDSMethodUrl: string,
    threeDSServerTransID: string,
    notificationUrl: string,
    timeoutMs = 10_000,
  ): Promise<FingerprintResult> {
    return new Promise((resolve) => {
      // Create hidden iframe
      this.iframe = document.createElement('iframe');
      this.iframe.setAttribute('name', 'nexuspay-3ds-fingerprint');
      this.iframe.setAttribute('title', 'Device verification');
      Object.assign(this.iframe.style, {
        width: '0',
        height: '0',
        border: 'none',
        position: 'absolute',
        top: '-9999px',
        left: '-9999px',
        visibility: 'hidden',
      });

      document.body.appendChild(this.iframe);

      // Create form that POSTs to the iframe
      const form = document.createElement('form');
      form.setAttribute('method', 'POST');
      form.setAttribute('action', threeDSMethodUrl);
      form.setAttribute('target', 'nexuspay-3ds-fingerprint');

      // 3DS Method Data — base64url encoded JSON
      const methodData = btoa(
        JSON.stringify({
          threeDSServerTransID,
          threeDSMethodNotificationURL: notificationUrl,
        }),
      );

      const input = document.createElement('input');
      input.type = 'hidden';
      input.name = 'threeDSMethodData';
      input.value = methodData;
      form.appendChild(input);

      document.body.appendChild(form);

      // Listen for completion
      const onComplete = () => {
        this.cleanup();
        resolve({ success: true, threeDSMethodData: methodData });
      };

      // The iframe will eventually navigate / complete
      if (this.iframe) {
        this.iframe.addEventListener('load', onComplete);
      }

      // Timeout — fingerprint collection should be fast
      this.timeout = setTimeout(() => {
        this.cleanup();
        resolve({
          success: false,
          error: `3DS fingerprint collection timed out after ${timeoutMs}ms`,
        });
      }, timeoutMs);

      // Submit the form
      form.submit();

      // Clean up form immediately (it's already submitted)
      if (form.parentElement) {
        form.parentElement.removeChild(form);
      }
    });
  }

  /** Cancels any in-progress fingerprint collection. */
  cancel(): void {
    this.cleanup();
  }

  private cleanup(): void {
    if (this.timeout) {
      clearTimeout(this.timeout);
      this.timeout = null;
    }
    if (this.iframe?.parentElement) {
      this.iframe.parentElement.removeChild(this.iframe);
    }
    this.iframe = null;
  }
}
