/**
 * TEST-5 (E4): a documented, typed way to inject a FAKE HTTP transport so you can
 * unit-test code that uses {@link NexusPay} WITHOUT any network.
 *
 * The client already accepts an injectable `fetch` (`NexusPayOptions.fetch`); this
 * file just supplies a convenient, typed factory for that seam — it does NOT
 * re-architect the client. Pass the result straight in:
 *
 *   import { NexusPay, createTestTransport } from '@nexus-pay/node';
 *
 *   const transport = createTestTransport({
 *     'GET /v1/ping': () => ({ status: 200, body: { ok: true, livemode: false, api_version: '2026-06-16' } }),
 *     'POST /v1/payments': (req) => ({ status: 201, body: { id: 'pay_test_1', status: 'succeeded', ...req.body } }),
 *   });
 *   const client = new NexusPay({ apiKey: 'sk_test_x', baseUrl: 'https://api.test', fetch: transport });
 *   await client.ping();
 *   transport.calls; // -> [{ method:'GET', path:'/v1/ping', url, headers, body }]
 */

/** A single recorded request the transport observed. */
export interface RecordedCall {
  method: string;
  /** The path (+ query) with the base origin stripped, e.g. `/v1/ping`. */
  path: string;
  /** The full request URL as the client built it. */
  url: string;
  /** Request headers (lower-cased keys), incl. the `authorization` Bearer header. */
  headers: Record<string, string>;
  /** The parsed JSON request body, or `undefined` for a GET / no-body request. */
  body: unknown;
}

/** What a handler is given about the inbound request. */
export interface TestTransportRequest extends RecordedCall {}

/** What a handler returns: a status + an optional JSON body + optional headers. */
export interface TestTransportResponseSpec {
  /** HTTP status. Defaults to 200. A non-2xx body should be the `{ error: {...} }` envelope. */
  status?: number;
  /** The JSON body to return. Serialized with `JSON.stringify`. */
  body?: unknown;
  /** Extra response headers (e.g. `{ 'retry-after': '1' }`). */
  headers?: Record<string, string>;
}

/** A handler: maps a request to a canned response spec. May be async. */
export type TestTransportHandler = (
  req: TestTransportRequest,
) => TestTransportResponseSpec | Promise<TestTransportResponseSpec>;

/**
 * Handlers keyed by `"<METHOD> <path>"`, e.g. `"GET /v1/ping"`. The method is
 * upper-cased and the path is matched WITHOUT query string.
 */
export type TestTransportHandlers = Record<string, TestTransportHandler>;

/** A `typeof fetch`-compatible function that also exposes the recorded `calls`. */
export type TestTransport = typeof fetch & { readonly calls: RecordedCall[] };

/** The first arg of `fetch` (the request input) — derived to avoid naming DOM globals. */
type FetchInput = Parameters<typeof fetch>[0];
/** The `headers` field of a `RequestInit` (the only init shape the client passes). */
type FetchHeaders = NonNullable<RequestInit['headers']>;

export interface CreateTestTransportOptions {
  /**
   * Called for a request whose `"<METHOD> <path>"` has no handler. Defaults to a
   * 404 `{ error: { type:'api_error', code:'no_test_handler', message } }`
   * envelope so `NexusPayError.fromEnvelope` maps it. Set to `'throw'` to throw a
   * clear `Error` instead.
   */
  onUnmatched?: TestTransportHandler | 'throw';
}

function buildErrorEnvelope(code: string, message: string): unknown {
  return { error: { type: 'api_error', code, message } };
}

function specToResponse(spec: TestTransportResponseSpec): Response {
  const status = spec.status ?? 200;
  const headers = new Headers(spec.headers ?? {});
  if (!headers.has('content-type')) headers.set('content-type', 'application/json');
  const payload = spec.body === undefined ? '' : JSON.stringify(spec.body);
  return new Response(payload, { status, headers });
}

function headersToObject(init: FetchHeaders | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  if (!init) return out;
  // The client always passes a plain object for headers (client.ts requestOnce),
  // but handle Headers / entries form too for robustness.
  if (init instanceof Headers) {
    init.forEach((v, k) => {
      out[k.toLowerCase()] = v;
    });
  } else if (Array.isArray(init)) {
    for (const [k, v] of init) out[String(k).toLowerCase()] = String(v);
  } else {
    for (const [k, v] of Object.entries(init)) out[k.toLowerCase()] = String(v);
  }
  return out;
}

function urlOf(input: FetchInput): string {
  if (typeof input === 'string') return input;
  if (input instanceof URL) return input.toString();
  // A Request object.
  return (input as Request).url;
}

function pathOf(url: string): string {
  try {
    const u = new URL(url);
    return u.pathname + u.search;
  } catch {
    // Not absolute — strip a leading origin-less form; return as-is.
    return url;
  }
}

function parseBody(init: RequestInit | undefined): unknown {
  const body = init?.body;
  if (body === undefined || body === null) return undefined;
  if (typeof body === 'string') {
    try {
      return JSON.parse(body);
    } catch {
      return body;
    }
  }
  return body;
}

/**
 * TEST-5 (E4): builds a `typeof fetch`-compatible transport that maps
 * `"<METHOD> <path>"` to a canned response and RECORDS every call on `.calls`.
 *
 * 2xx handlers return your canned `body`; a non-2xx body should be the
 * `{ error: { type, code, message } }` envelope so the client's
 * `NexusPayError.fromEnvelope` maps it. Returns real `Response` objects (Node
 * 18+), so `.ok` / `.status` / `.json()` / `.headers.get('retry-after')` — all
 * the surfaces `client.requestOnce` consumes — work unchanged.
 */
export function createTestTransport(
  handlers: TestTransportHandlers,
  options?: CreateTestTransportOptions,
): TestTransport {
  const calls: RecordedCall[] = [];

  const transport = (async (
    input: FetchInput,
    init?: RequestInit,
  ): Promise<Response> => {
    const url = urlOf(input);
    const method = (init?.method ?? 'GET').toUpperCase();
    const path = pathOf(url);
    // Match against the path WITHOUT query string.
    const pathNoQuery = path.split('?')[0];
    const headers = headersToObject(init?.headers);
    const body = parseBody(init);

    const record: RecordedCall = { method, path, url, headers, body };
    calls.push(record);

    const key = `${method} ${pathNoQuery}`;
    const handler = handlers[key];
    if (handler) {
      return specToResponse(await handler(record));
    }

    const onUnmatched = options?.onUnmatched;
    if (onUnmatched === 'throw') {
      throw new Error(`createTestTransport: no handler for "${key}"`);
    }
    if (typeof onUnmatched === 'function') {
      return specToResponse(await onUnmatched(record));
    }
    return specToResponse({
      status: 404,
      body: buildErrorEnvelope('no_test_handler', `No test handler for "${key}"`),
    });
  }) as TestTransport;

  Object.defineProperty(transport, 'calls', {
    value: calls,
    enumerable: true,
    writable: false,
  });

  return transport;
}
