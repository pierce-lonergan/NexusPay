/**
 * `nexuspay listen [opts]` — a local webhook receiver (Stripe-CLI-style loop).
 *
 * Starts a node:http server bound to LOOPBACK ONLY (127.0.0.1), verifies each
 * incoming POST with the SDK's `verifyWebhook` / `constructEvent` (constant-time,
 * never hand-rolled here so it can't drift from the platform signer), prints a
 * concise per-event line + the pretty body, and OPTIONALLY relays the raw bytes
 * to a local app via `--forward-to` — but ONLY after a successful verify, and
 * ONLY to a loopback target unless `--allow-remote`.
 *
 * Security:
 *  - Bind 127.0.0.1 ONLY. A non-loopback bind would expose the local receiver to
 *    the network; this tool receives test webhooks locally and must stay private.
 *  - The webhook secret is NEVER printed/logged on any path (stdout, stderr,
 *    errors). Only the SDK boolean verify result + the parsed event are shown.
 *  - Verification reuses verifyWebhook/constructEvent (timingSafeEqual). No HMAC
 *    is computed in this file.
 *  - --forward-to forwards ONLY after verify, and refuses a non-loopback target
 *    unless --allow-remote (no open relay).
 */

import http from 'node:http';
import { verifyWebhook, constructEvent } from '../webhooks';
import type { ParsedArgs } from './args';

/** Loopback bind address — never 0.0.0.0. */
const LOOPBACK_HOST = '127.0.0.1';
const DEFAULT_PORT = 4242;

const SIGNATURE_HEADER = 'x-nexuspay-signature';
const TIMESTAMP_HEADER = 'x-nexuspay-timestamp';
const EVENT_HEADER = 'x-nexuspay-event';

export interface ListenWriters {
  stdout?: (line: string) => void;
  stderr?: (line: string) => void;
}

export interface ListenServerOptions extends ListenWriters {
  /** The endpoint signing secret. Held only in the closure; never printed. */
  secret: string;
  /** Optional forward target (local app URL). */
  forwardTo?: string;
  /** Allow a non-loopback forward target (escape hatch; default false). */
  allowRemote?: boolean;
  /** Injectable fetch for forwarding (defaults to the global fetch). */
  fetchImpl?: typeof fetch;
}

/** True if a URL's host is loopback (127.0.0.1 / localhost / ::1). */
export function isLoopbackTarget(url: string): boolean {
  let host: string;
  try {
    host = new URL(url).hostname;
  } catch {
    return false;
  }
  // `URL.hostname` keeps the brackets on an IPv6 host (e.g. "[::1]"); strip them.
  if (host.startsWith('[') && host.endsWith(']')) {
    host = host.slice(1, -1);
  }
  return host === '127.0.0.1' || host === 'localhost' || host === '::1';
}

function headerValue(
  headers: http.IncomingHttpHeaders,
  name: string,
): string | undefined {
  const v = headers[name];
  if (Array.isArray(v)) return v[v.length - 1];
  return v;
}

/**
 * Builds the node:http server WITHOUT calling `.listen` (so tests can drive the
 * handler or bind an ephemeral port). The secret lives only in this closure.
 */
export function createListenServer(opts: ListenServerOptions): http.Server {
  const stdout = opts.stdout ?? ((l: string) => process.stdout.write(l + '\n'));
  const stderr = opts.stderr ?? ((l: string) => process.stderr.write(l + '\n'));
  const fetchImpl = opts.fetchImpl ?? globalThis.fetch;

  return http.createServer((req, res) => {
    if (req.method !== 'POST') {
      res.statusCode = 405;
      res.setHeader('Allow', 'POST');
      res.end('Method Not Allowed\n');
      return;
    }

    const chunks: Buffer[] = [];
    req.on('data', (chunk: Buffer) => {
      chunks.push(chunk);
    });
    req.on('error', () => {
      res.statusCode = 400;
      res.end('Bad Request\n');
    });
    req.on('end', () => {
      // Preserve the EXACT raw bytes — verifyWebhook signs raw, not re-serialized.
      const rawBody = Buffer.concat(chunks);
      const sigHeader = headerValue(req.headers, SIGNATURE_HEADER);

      const verified = sigHeader
        ? verifyWebhook(rawBody, sigHeader, opts.secret)
        : false;

      if (!verified) {
        stderr('verified=false (bad or missing signature) -> 400');
        res.statusCode = 400;
        res.end('signature verification failed\n');
        return;
      }

      // Verified: parse for a concise line + pretty body. constructEvent
      // re-verifies (cheap) and JSON-parses.
      let type = '(unknown)';
      let id = '(unknown)';
      let pretty = rawBody.toString('utf8');
      try {
        const event = constructEvent(rawBody, req.headers, opts.secret);
        type = event.type;
        id = event.id;
        pretty = JSON.stringify(event, null, 2);
      } catch {
        // Verified by HMAC but unparseable JSON: still report what we can.
        type = headerValue(req.headers, EVENT_HEADER) ?? '(unparseable)';
      }

      stdout(`type=${type} id=${id} verified=true`);
      stdout(pretty);

      res.statusCode = 200;
      res.end('ok\n');

      // Relay ONLY after a successful verify, and ONLY to an allowed target.
      if (opts.forwardTo) {
        void forward(opts.forwardTo, rawBody, req.headers, {
          allowRemote: opts.allowRemote === true,
          fetchImpl,
          stderr,
        });
      }
    });
  });
}

interface ForwardCtx {
  allowRemote: boolean;
  fetchImpl: typeof fetch;
  stderr: (line: string) => void;
}

async function forward(
  target: string,
  rawBody: Buffer,
  headers: http.IncomingHttpHeaders,
  ctx: ForwardCtx,
): Promise<void> {
  if (!ctx.allowRemote && !isLoopbackTarget(target)) {
    ctx.stderr(
      `error: refusing to forward to a non-loopback target (${target}). ` +
        'Pass --allow-remote to override (avoid open relays).',
    );
    return;
  }

  const fwdHeaders: Record<string, string> = {
    'content-type': headerValue(headers, 'content-type') ?? 'application/json',
  };
  const sig = headerValue(headers, SIGNATURE_HEADER);
  const ts = headerValue(headers, TIMESTAMP_HEADER);
  const ev = headerValue(headers, EVENT_HEADER);
  if (sig) fwdHeaders[SIGNATURE_HEADER] = sig;
  if (ts) fwdHeaders[TIMESTAMP_HEADER] = ts;
  if (ev) fwdHeaders[EVENT_HEADER] = ev;

  try {
    await ctx.fetchImpl(target, {
      method: 'POST',
      headers: fwdHeaders,
      body: rawBody,
    });
  } catch (err) {
    ctx.stderr(`error: forward to ${target} failed: ${messageOf(err)}`);
  }
}

export const LISTEN_USAGE = `Usage: nexuspay listen [options]

Receive webhooks locally on a loopback (127.0.0.1) HTTP server, verify each
signature with the SDK, and optionally forward verified deliveries to your app.

Options:
  --port <n>              Port to listen on (default ${DEFAULT_PORT})
  --secret <whsec_...>    Endpoint signing secret (or env NEXUSPAY_WEBHOOK_SECRET)
  --forward-to <url>      Relay verified deliveries to a local app URL
  --allow-remote          Allow a non-loopback --forward-to target (open-relay risk)
  -h, --help              Show this help

Binds 127.0.0.1 only. The signing secret is never printed.`;

export interface ListenDeps extends ListenWriters {
  env?: NodeJS.ProcessEnv;
  /** Injectable server factory (tests). Defaults to createListenServer. */
  makeServer?: (opts: ListenServerOptions) => http.Server;
  /** Injectable SIGINT/exit hookup (tests). */
  onSigint?: (handler: () => void) => void;
}

/**
 * Binds the listen server (loopback only) and wires a clean SIGINT shutdown.
 * Resolves with the intended exit code. (In real use it stays pending until
 * SIGINT; tests inject `onSigint`/`makeServer`.)
 */
export async function runListen(parsed: ParsedArgs, deps: ListenDeps = {}): Promise<number> {
  const env = deps.env ?? process.env;
  const stdout = deps.stdout ?? ((l: string) => process.stdout.write(l + '\n'));
  const stderr = deps.stderr ?? ((l: string) => process.stderr.write(l + '\n'));

  if (parsed.help) {
    stdout(LISTEN_USAGE);
    return 0;
  }

  const secret = asString(parsed.flags.secret) ?? env.NEXUSPAY_WEBHOOK_SECRET;
  if (!secret) {
    stderr(
      'error: no webhook secret. Pass --secret or set NEXUSPAY_WEBHOOK_SECRET.',
    );
    return 2;
  }

  const portRaw = asString(parsed.flags.port);
  let port = DEFAULT_PORT;
  if (portRaw !== undefined) {
    const n = Number(portRaw);
    if (!Number.isInteger(n) || n < 1 || n > 65535) {
      stderr(`error: --port must be an integer 1-65535 (got ${portRaw})`);
      return 2;
    }
    port = n;
  }

  const forwardTo = asString(parsed.flags['forward-to']);
  const allowRemote = parsed.flags['allow-remote'] === true;

  // Refuse a non-loopback forward target up front (before binding) unless allowed.
  if (forwardTo && !allowRemote && !isLoopbackTarget(forwardTo)) {
    stderr(
      `error: --forward-to ${forwardTo} is not a loopback target. ` +
        'Pass --allow-remote to override (avoid open relays).',
    );
    return 2;
  }

  const makeServer = deps.makeServer ?? createListenServer;
  const server = makeServer({
    secret,
    forwardTo,
    allowRemote,
    stdout,
    stderr,
  });

  return await new Promise<number>((resolve) => {
    server.on('error', (err) => {
      stderr(`error: ${messageOf(err)}`);
      resolve(1);
    });

    // Bind LOOPBACK ONLY — never 0.0.0.0.
    server.listen(port, LOOPBACK_HOST, () => {
      stdout(`listening on http://${LOOPBACK_HOST}:${port}`);
      if (forwardTo) stdout(`forwarding verified deliveries to ${forwardTo}`);
    });

    const shutdown = () => {
      server.close(() => resolve(0));
    };
    if (deps.onSigint) {
      deps.onSigint(shutdown);
    } else {
      process.once('SIGINT', shutdown);
    }
  });
}

function asString(v: string | boolean | undefined): string | undefined {
  return typeof v === 'string' ? v : undefined;
}

function messageOf(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}
