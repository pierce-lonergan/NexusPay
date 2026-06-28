import { describe, it, expect, vi } from 'vitest';
import { parseArgs } from '../bin/args';
import { runTrigger, type TriggerClient } from '../bin/trigger';
import { NexusPayError } from '../errors';
import type { TestEvent } from '../types';

const SECRET_KEY = 'sk_test_supersecret_abc123';

function capture() {
  const out: string[] = [];
  const err: string[] = [];
  return {
    out,
    err,
    stdout: (l: string) => out.push(l),
    stderr: (l: string) => err.push(l),
    joined: () => [...out, ...err].join('\n'),
  };
}

const okEvent: TestEvent = {
  id: 'evt_test_1',
  type: 'payment.succeeded',
  livemode: false,
  object: { id: 'pay_1' },
};

describe('runTrigger', () => {
  it('builds the right triggerTestEvent call and prints the result', async () => {
    const c = capture();
    const triggerTestEvent = vi.fn().mockResolvedValue(okEvent);
    const makeClient = vi.fn(
      (): TriggerClient => ({ triggerTestEvent }),
    );

    const parsed = parseArgs([
      'trigger',
      'payment.succeeded',
      '--key',
      SECRET_KEY,
      '--base-url',
      'http://localhost:8090',
      '--id',
      'pay_override',
      '--data',
      '{"amount":1000}',
    ]);

    const code = await runTrigger(parsed, {
      makeClient,
      stdout: c.stdout,
      stderr: c.stderr,
      env: {},
    });

    expect(code).toBe(0);
    expect(makeClient).toHaveBeenCalledWith({
      apiKey: SECRET_KEY,
      baseUrl: 'http://localhost:8090',
    });
    expect(triggerTestEvent).toHaveBeenCalledWith({
      type: 'payment.succeeded',
      id: 'pay_override',
      data: { amount: 1000 },
    });
    expect(JSON.parse(c.out[0])).toEqual({
      id: 'evt_test_1',
      type: 'payment.succeeded',
      livemode: false,
    });
  });

  it('reads key + base-url from env (incl. aliases)', async () => {
    const c = capture();
    const triggerTestEvent = vi.fn().mockResolvedValue(okEvent);
    const parsed = parseArgs(['trigger', 'payment.succeeded']);

    const code = await runTrigger(parsed, {
      makeClient: () => ({ triggerTestEvent }),
      stdout: c.stdout,
      stderr: c.stderr,
      env: { NEXUSPAY_API_KEY: SECRET_KEY, NEXUSPAY_API_URL: 'http://localhost:8090' },
    });

    expect(code).toBe(0);
    expect(triggerTestEvent).toHaveBeenCalled();
  });

  it('refuses an sk_live_ key WITHOUT calling the client or leaking the key', async () => {
    const c = capture();
    const liveKey = 'sk_live_dangerous_zzz';
    const makeClient = vi.fn();

    const parsed = parseArgs([
      'trigger',
      'payment.succeeded',
      '--key',
      liveKey,
      '--base-url',
      'http://localhost:8090',
    ]);

    const code = await runTrigger(parsed, {
      makeClient,
      stdout: c.stdout,
      stderr: c.stderr,
      env: {},
    });

    expect(code).not.toBe(0);
    expect(makeClient).not.toHaveBeenCalled();
    expect(c.joined()).toMatch(/live key/i);
    // The key must NOT appear anywhere in the output.
    expect(c.joined()).not.toContain(liveKey);
  });

  it('surfaces a NexusPayError message to stderr WITHOUT leaking the key', async () => {
    const c = capture();
    const triggerTestEvent = vi.fn().mockRejectedValue(
      new NexusPayError({
        type: 'not_found',
        code: 'http_404',
        message: 'test events are only available with a test key',
        status: 404,
      }),
    );

    const parsed = parseArgs([
      'trigger',
      'payment.succeeded',
      '--key',
      SECRET_KEY,
      '--base-url',
      'http://localhost:8090',
    ]);

    const code = await runTrigger(parsed, {
      makeClient: () => ({ triggerTestEvent }),
      stdout: c.stdout,
      stderr: c.stderr,
      env: {},
    });

    expect(code).toBe(1);
    expect(c.err.join('\n')).toMatch(/only available with a test key/);
    expect(c.joined()).not.toContain(SECRET_KEY);
  });

  it('errors clearly when the base URL is missing', async () => {
    const c = capture();
    const parsed = parseArgs(['trigger', 'payment.succeeded', '--key', SECRET_KEY]);
    const code = await runTrigger(parsed, {
      makeClient: () => ({ triggerTestEvent: vi.fn() }),
      stdout: c.stdout,
      stderr: c.stderr,
      env: {},
    });
    expect(code).toBe(2);
    expect(c.err.join('\n')).toMatch(/base URL/i);
  });

  it('errors clearly when the key is missing', async () => {
    const c = capture();
    const parsed = parseArgs(['trigger', 'payment.succeeded', '--base-url', 'http://x']);
    const code = await runTrigger(parsed, {
      makeClient: () => ({ triggerTestEvent: vi.fn() }),
      stdout: c.stdout,
      stderr: c.stderr,
      env: {},
    });
    expect(code).toBe(2);
    expect(c.err.join('\n')).toMatch(/API key/i);
  });

  it('errors on malformed --data JSON', async () => {
    const c = capture();
    const triggerTestEvent = vi.fn();
    const parsed = parseArgs([
      'trigger',
      'payment.succeeded',
      '--key',
      SECRET_KEY,
      '--base-url',
      'http://localhost:8090',
      '--data',
      '{not json',
    ]);
    const code = await runTrigger(parsed, {
      makeClient: () => ({ triggerTestEvent }),
      stdout: c.stdout,
      stderr: c.stderr,
      env: {},
    });
    expect(code).toBe(2);
    expect(c.err.join('\n')).toMatch(/not valid JSON/i);
    expect(triggerTestEvent).not.toHaveBeenCalled();
  });

  it('rejects an unknown event type client-side WITHOUT building a client', async () => {
    const c = capture();
    const makeClient = vi.fn();
    const parsed = parseArgs([
      'trigger',
      'payment.succeded', // typo: missing the second 'e'
      '--key',
      SECRET_KEY,
      '--base-url',
      'http://localhost:8090',
    ]);
    const code = await runTrigger(parsed, {
      makeClient,
      stdout: c.stdout,
      stderr: c.stderr,
      env: {},
    });
    expect(code).toBe(2);
    expect(makeClient).not.toHaveBeenCalled();
    expect(c.err.join('\n')).toMatch(/unknown event type/i);
    // It should suggest the nearest canonical type for a single-char typo.
    expect(c.err.join('\n')).toMatch(/payment\.succeeded/);
    // Never leak the key, even on a client-side rejection.
    expect(c.joined()).not.toContain(SECRET_KEY);
  });

  it('errors when event_type is missing', async () => {
    const c = capture();
    const parsed = parseArgs(['trigger']);
    const code = await runTrigger(parsed, {
      makeClient: () => ({ triggerTestEvent: vi.fn() }),
      stdout: c.stdout,
      stderr: c.stderr,
      env: {},
    });
    expect(code).toBe(2);
    expect(c.err.join('\n')).toMatch(/event_type/i);
  });

  it('prints help with --help', async () => {
    const c = capture();
    const parsed = parseArgs(['trigger', '--help']);
    const code = await runTrigger(parsed, { stdout: c.stdout, stderr: c.stderr, env: {} });
    expect(code).toBe(0);
    expect(c.out.join('\n')).toMatch(/Usage: nexuspay trigger/);
  });
});
