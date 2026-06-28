/**
 * Tests for the PRODUCTION `runListen` entry point — specifically the two
 * security-critical paths that have no other coverage:
 *
 *  1. The loopback bind. runListen is the code that actually calls
 *     `server.listen(port, LOOPBACK_HOST, ...)`. We inject a fake server that
 *     records the (port, host) passed to `.listen`, so flipping LOOPBACK_HOST to
 *     '0.0.0.0' in listen.ts would FAIL this test (the earlier createListenServer
 *     test hardcodes the host itself and is tautological).
 *
 *  2. The up-front open-relay guard: a non-loopback --forward-to without
 *     --allow-remote must return exit 2 BEFORE binding (no server is even made),
 *     and --allow-remote must let it proceed (allowRemote:true reaches makeServer).
 */
import { describe, it, expect, vi } from 'vitest';
import type http from 'node:http';
import { parseArgs } from '../bin/args';
import { runListen, type ListenServerOptions } from '../bin/listen';

const SECRET = 'whsec_test_topsecret_value_999';

interface FakeServer {
  server: http.Server;
  listened: { port?: number; host?: string };
}

/**
 * A minimal stand-in for http.Server: records the (port, host) handed to
 * `.listen`, fires the callback synchronously so runListen's promise resolves
 * via the injected SIGINT handler, and supports `.on('error')` + `.close(cb)`.
 */
function makeFakeServer(): FakeServer {
  const listened: { port?: number; host?: string } = {};
  const server = {
    on() {
      return this;
    },
    listen(port: number, host: string, cb?: () => void) {
      listened.port = port;
      listened.host = host;
      if (cb) cb();
      return this;
    },
    close(cb?: () => void) {
      if (cb) cb();
      return this;
    },
  } as unknown as http.Server;
  return { server, listened };
}

function capture() {
  const out: string[] = [];
  const err: string[] = [];
  return {
    out,
    err,
    stdout: (l: string) => out.push(l),
    stderr: (l: string) => err.push(l),
  };
}

describe('runListen (production bind + guards)', () => {
  it('binds to 127.0.0.1 (loopback) — the production bind, not the test', async () => {
    const c = capture();
    const fake = makeFakeServer();
    const makeServer = vi.fn((_: ListenServerOptions) => fake.server);

    const parsed = parseArgs(['listen', '--port', '4242', '--secret', SECRET]);
    let sigint: (() => void) | undefined;
    const code = await runListen(parsed, {
      stdout: c.stdout,
      stderr: c.stderr,
      env: {},
      makeServer,
      onSigint: (h) => {
        sigint = h;
        // Trigger a clean shutdown immediately so the promise resolves.
        h();
      },
    });

    expect(code).toBe(0);
    expect(sigint).toBeTypeOf('function');
    // THE assertion that matters: production code passed loopback to .listen.
    expect(fake.listened.host).toBe('127.0.0.1');
    expect(fake.listened.port).toBe(4242);
  });

  it('refuses a non-loopback --forward-to without --allow-remote (exit 2, no bind)', async () => {
    const c = capture();
    const makeServer = vi.fn();
    const parsed = parseArgs([
      'listen',
      '--secret',
      SECRET,
      '--forward-to',
      'http://example.com/hook',
    ]);
    const code = await runListen(parsed, {
      stdout: c.stdout,
      stderr: c.stderr,
      env: {},
      makeServer,
      onSigint: () => {},
    });

    expect(code).toBe(2);
    // The guard runs BEFORE binding — no server is ever created.
    expect(makeServer).not.toHaveBeenCalled();
    expect(c.err.join('\n')).toMatch(/loopback/i);
  });

  it('allows a non-loopback --forward-to WITH --allow-remote (passes allowRemote:true)', async () => {
    const c = capture();
    const fake = makeFakeServer();
    const makeServer = vi.fn((_: ListenServerOptions) => fake.server);
    const parsed = parseArgs([
      'listen',
      '--secret',
      SECRET,
      '--forward-to',
      'http://example.com/hook',
      '--allow-remote',
    ]);
    const code = await runListen(parsed, {
      stdout: c.stdout,
      stderr: c.stderr,
      env: {},
      makeServer,
      onSigint: (h) => h(),
    });

    expect(code).toBe(0);
    expect(makeServer).toHaveBeenCalledTimes(1);
    const opts = makeServer.mock.calls[0][0] as ListenServerOptions;
    expect(opts.allowRemote).toBe(true);
    expect(opts.forwardTo).toBe('http://example.com/hook');
    // It still binds loopback even when forwarding remotely.
    expect(fake.listened.host).toBe('127.0.0.1');
  });

  it('never prints the secret (runListen path)', async () => {
    const c = capture();
    const fake = makeFakeServer();
    const parsed = parseArgs(['listen', '--secret', SECRET]);
    await runListen(parsed, {
      stdout: c.stdout,
      stderr: c.stderr,
      env: {},
      makeServer: () => fake.server,
      onSigint: (h) => h(),
    });
    expect([...c.out, ...c.err].join('\n')).not.toContain(SECRET);
  });
});
