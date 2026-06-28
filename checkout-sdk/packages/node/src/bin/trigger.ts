/**
 * `nexuspay trigger <event_type> [opts]` — fire a TEST-MODE webhook through the
 * platform's signed delivery pipeline (wraps NexusPay.triggerTestEvent).
 *
 * Security:
 *  - REFUSES an `sk_live_` key client-side (fail-fast): the trigger is test-only
 *    (the server also 404s a live key, but we fail before constructing the client
 *    with a clear message).
 *  - NEVER prints the API key — on error only `err.message` is written.
 *
 * Dependency-injectable (`deps.makeClient`, `deps.stdout`, `deps.stderr`) so
 * tests exercise it without real HTTP.
 */

import { NexusPay, type NexusPayOptions } from '../client';
import { NexusPayError } from '../errors';
import { WEBHOOK_EVENT_TYPES } from '../types';
import type { TriggerTestEventParams, TestEvent, WebhookEventType } from '../types';
import type { ParsedArgs } from './args';

export interface TriggerClient {
  triggerTestEvent(params: TriggerTestEventParams): Promise<TestEvent>;
}

export interface TriggerDeps {
  /** Factory for the client; defaults to a real NexusPay. Injected in tests. */
  makeClient?: (opts: NexusPayOptions) => TriggerClient;
  stdout?: (line: string) => void;
  stderr?: (line: string) => void;
  env?: NodeJS.ProcessEnv;
}

export const TRIGGER_USAGE = `Usage: nexuspay trigger <event_type> [options]

Fire a TEST-MODE webhook to your tenant's endpoints through the signed
delivery pipeline (so you can exercise your receiver without a real payment).

Arguments:
  <event_type>            Canonical event type, e.g. payment.succeeded

Options:
  --key <sk_test_...>     Test secret key (or env NEXUSPAY_SECRET_KEY / NEXUSPAY_API_KEY)
  --base-url <url>        API base URL (or env NEXUSPAY_BASE_URL / NEXUSPAY_API_URL)
  --id <id>               Optional test aggregate id
  --data <json>           Optional JSON overlay merged onto data.object
  -h, --help              Show this help

A live key (sk_live_) is refused: the trigger is test-mode only.`;

/**
 * Runs the trigger subcommand. Returns the intended process exit code
 * (0 success, non-zero on any error). Never throws for an expected failure —
 * it writes a clear message to stderr and returns a non-zero code.
 */
export async function runTrigger(parsed: ParsedArgs, deps: TriggerDeps = {}): Promise<number> {
  const env = deps.env ?? process.env;
  const stdout = deps.stdout ?? ((l: string) => process.stdout.write(l + '\n'));
  const stderr = deps.stderr ?? ((l: string) => process.stderr.write(l + '\n'));
  const makeClient = deps.makeClient ?? ((o: NexusPayOptions) => new NexusPay(o));

  if (parsed.help) {
    stdout(TRIGGER_USAGE);
    return 0;
  }

  const eventType = parsed.positionals[0];
  if (!eventType) {
    stderr('error: missing <event_type>');
    stderr(TRIGGER_USAGE);
    return 2;
  }

  // Validate the event type client-side against the authoritative canonical list
  // (WEBHOOK_EVENT_TYPES, exported from the package). A typo like
  // `payment.succeded` fails FAST here with an actionable message instead of a
  // slow, opaque server error after a network round-trip — and it makes the
  // `as WebhookEventType` cast below safe rather than a blind assertion.
  if (!(WEBHOOK_EVENT_TYPES as readonly string[]).includes(eventType)) {
    const suggestions = nearestEventTypes(eventType);
    stderr(`error: unknown event type: ${eventType}`);
    if (suggestions.length > 0) {
      stderr(`  did you mean: ${suggestions.join(', ')}?`);
    }
    stderr(
      `  run \`nexuspay trigger --help\` or use a canonical type, e.g. payment.succeeded`,
    );
    return 2;
  }

  const apiKey =
    asString(parsed.flags.key) ?? env.NEXUSPAY_SECRET_KEY ?? env.NEXUSPAY_API_KEY;
  if (!apiKey) {
    stderr(
      'error: no API key. Pass --key or set NEXUSPAY_SECRET_KEY (alias NEXUSPAY_API_KEY).',
    );
    return 2;
  }

  // FAIL FAST: a live key must never be pointed at a test-only trigger. Refuse
  // client-side BEFORE constructing the client (the server also 404s, but this
  // is the helpful message). Never echo the key.
  if (apiKey.startsWith('sk_live_')) {
    stderr(
      'error: refusing a live key (sk_live_) — `nexuspay trigger` only works in test mode. ' +
        'Use your sk_test_ key.',
    );
    return 2;
  }

  const baseUrl =
    asString(parsed.flags['base-url']) ?? env.NEXUSPAY_BASE_URL ?? env.NEXUSPAY_API_URL;
  if (!baseUrl) {
    stderr(
      'error: no base URL. Pass --base-url or set NEXUSPAY_BASE_URL (alias NEXUSPAY_API_URL), ' +
        'e.g. --base-url http://localhost:8090',
    );
    return 2;
  }

  const id = asString(parsed.flags.id);

  let data: Record<string, unknown> | undefined;
  const rawData = asString(parsed.flags.data);
  if (rawData !== undefined) {
    try {
      const parsedData = JSON.parse(rawData);
      if (typeof parsedData !== 'object' || parsedData === null || Array.isArray(parsedData)) {
        stderr('error: --data must be a JSON object');
        return 2;
      }
      data = parsedData as Record<string, unknown>;
    } catch {
      stderr('error: --data is not valid JSON');
      return 2;
    }
  }

  let client: TriggerClient;
  try {
    client = makeClient({ apiKey, baseUrl });
  } catch (err) {
    // Constructor validation (e.g. missing field) — print message only.
    stderr(`error: ${messageOf(err)}`);
    return 1;
  }

  try {
    const event = await client.triggerTestEvent({
      type: eventType as WebhookEventType,
      id,
      data,
    });
    stdout(JSON.stringify({ id: event.id, type: event.type, livemode: event.livemode }));
    return 0;
  } catch (err) {
    // NEVER leak the key: print only the error message.
    if (err instanceof NexusPayError) {
      stderr(`error: ${err.message}`);
    } else {
      stderr(`error: ${messageOf(err)}`);
    }
    return 1;
  }
}

function asString(v: string | boolean | undefined): string | undefined {
  return typeof v === 'string' ? v : undefined;
}

/**
 * Best-effort "did you mean" suggestions for a mistyped event type: the canonical
 * types within a small edit distance of the input (catches single-char typos like
 * `payment.succeded`). Purely for a friendlier error — never affects behavior.
 */
function nearestEventTypes(input: string): string[] {
  const max = 2;
  return (WEBHOOK_EVENT_TYPES as readonly string[])
    .map((t) => ({ t, d: levenshtein(input, t) }))
    .filter((x) => x.d > 0 && x.d <= max)
    .sort((a, b) => a.d - b.d)
    .slice(0, 3)
    .map((x) => x.t);
}

function levenshtein(a: string, b: string): number {
  const m = a.length;
  const n = b.length;
  if (m === 0) return n;
  if (n === 0) return m;
  let prev = Array.from({ length: n + 1 }, (_, i) => i);
  let curr = new Array<number>(n + 1);
  for (let i = 1; i <= m; i++) {
    curr[0] = i;
    for (let j = 1; j <= n; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      curr[j] = Math.min(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost);
    }
    [prev, curr] = [curr, prev];
  }
  return prev[n];
}

function messageOf(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}
