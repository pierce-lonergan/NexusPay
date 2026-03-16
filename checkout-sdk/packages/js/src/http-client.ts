/**
 * Fetch wrapper for NexusPay API calls.
 * Zero dependencies — uses native fetch and AbortController.
 *
 * - 10-second timeout via AbortController
 * - Single retry ONLY on network errors (fetch throws TypeError)
 * - NEVER retries on HTTP 4xx/5xx
 * - Automatic Bearer auth header
 * - Maps errors to NexusPayError
 */

import type { NexusPayError } from './types';

export interface HttpClientOptions {
  baseUrl: string;
  sessionToken?: string;
  timeout?: number;
}

const DEFAULT_TIMEOUT = 10_000; // 10 seconds

export class HttpClient {
  private baseUrl: string;
  private sessionToken: string | null;
  private timeout: number;

  constructor(options: HttpClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/$/, '');
    this.sessionToken = options.sessionToken ?? null;
    this.timeout = options.timeout ?? DEFAULT_TIMEOUT;
  }

  setSessionToken(token: string): void {
    this.sessionToken = token;
  }

  async get<T>(path: string): Promise<T> {
    return this.request<T>('GET', path);
  }

  async post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('POST', path, body);
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };

    if (this.sessionToken) {
      headers['Authorization'] = `Bearer ${this.sessionToken}`;
    }

    const init: RequestInit = {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    };

    try {
      return await this.fetchWithTimeout<T>(url, init);
    } catch (err) {
      // Retry ONLY on network errors (TypeError from fetch)
      if (err instanceof TypeError) {
        return this.fetchWithTimeout<T>(url, init);
      }
      throw err;
    }
  }

  private async fetchWithTimeout<T>(url: string, init: RequestInit): Promise<T> {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), this.timeout);

    try {
      const response = await fetch(url, { ...init, signal: controller.signal });

      if (!response.ok) {
        const errorBody = await response.json().catch(() => ({}));
        const error: NexusPayError = errorBody?.error ?? {
          type: response.status === 429 ? 'rate_limit_error' : 'api_error',
          code: `http_${response.status}`,
          message: `HTTP ${response.status}: ${response.statusText}`,
        };
        throw error;
      }

      return await response.json() as T;
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') {
        const timeoutError: NexusPayError = {
          type: 'network_error',
          code: 'timeout',
          message: `Request timed out after ${this.timeout}ms`,
        };
        throw timeoutError;
      }
      throw err;
    } finally {
      clearTimeout(timeoutId);
    }
  }
}
