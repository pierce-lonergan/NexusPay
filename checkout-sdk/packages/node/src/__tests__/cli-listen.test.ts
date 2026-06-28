import { describe, it, expect, afterEach } from 'vitest';
import http from 'node:http';
import { createHmac } from 'node:crypto';
import { AddressInfo } from 'node:net';
import {
  createListenServer,
  isLoopbackTarget,
  type ListenServerOptions,
} from '../bin/listen';

const SECRET = 'whsec_test_topsecret_value_999';

/** Builds a signed webhook body matching webhooks.computeSignature. */
function signedBody(payload: object): { raw: string; sig: string } {
  const raw = JSON.stringify(payload);
  const sig = createHmac('sha256', SECRET).update(raw).digest('hex');
  return { raw, sig };
}

const eventPayload = {
  id: 'evt_1',
  type: 'payment.succeeded',
  livemode: false,
  created: Math.floor(Date.now() / 1000),
  api_version: '2026-06-16',
  data: { object: { id: 'pay_1', object: 'payment' }, metadata: {} },
};

interface Started {
  server: http.Server;
  port: number;
  out: string[];
  err: string[];
}

const cleanups: Array<() => Promise<void>> = [];

afterEach(async () => {
  while (cleanups.length) {
    const fn = cleanups.pop()!;
    await fn();
  }
});

async function startListen(opts: Partial<ListenServerOptions> = {}): Promise<Started> {
  const out: string[] = [];
  const err: string[] = [];
  const server = createListenServer({
    secret: SECRET,
    stdout: (l) => out.push(l),
    stderr: (l) => err.push(l),
    ...opts,
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()));
  const port = (server.address() as AddressInfo).port;
  cleanups.push(() => new Promise<void>((r) => server.close(() => r())));
  return { server, port, out, err };
}

async function post(
  port: number,
  body: string,
  headers: Record<string, string>,
): Promise<{ status: number }> {
  const res = await fetch(`http://127.0.0.1:${port}/`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body,
  });
  await res.text();
  return { status: res.status };
}

describe('listen server', () => {
  it('binds to 127.0.0.1 (loopback)', async () => {
    const { server } = await startListen();
    expect((server.address() as AddressInfo).address).toBe('127.0.0.1');
  });

  it('accepts a correctly-signed body -> 200 + verified=true', async () => {
    const { port, out } = await startListen();
    const { raw, sig } = signedBody(eventPayload);
    const res = await post(port, raw, { 'x-nexuspay-signature': sig });
    expect(res.status).toBe(200);
    expect(out.join('\n')).toMatch(/verified=true/);
    expect(out.join('\n')).toMatch(/type=payment\.succeeded/);
    expect(out.join('\n')).toMatch(/id=evt_1/);
  });

  it('rejects a tampered signature -> 400 + verified=false', async () => {
    const { port, out, err } = await startListen();
    const { raw } = signedBody(eventPayload);
    const res = await post(port, raw, { 'x-nexuspay-signature': 'deadbeef'.repeat(8) });
    expect(res.status).toBe(400);
    expect(err.join('\n')).toMatch(/verified=false/);
    expect(out.join('\n')).not.toMatch(/verified=true/);
  });

  it('rejects a missing signature -> 400', async () => {
    const { port } = await startListen();
    const { raw } = signedBody(eventPayload);
    const res = await post(port, raw, {});
    expect(res.status).toBe(400);
  });

  it('rejects non-POST -> 405', async () => {
    const { port } = await startListen();
    const res = await fetch(`http://127.0.0.1:${port}/`, { method: 'GET' });
    await res.text();
    expect(res.status).toBe(405);
  });

  it('NEVER prints the secret (good or bad signature)', async () => {
    const { port, out, err } = await startListen();
    const { raw, sig } = signedBody(eventPayload);
    await post(port, raw, { 'x-nexuspay-signature': sig });
    await post(port, raw, { 'x-nexuspay-signature': 'bad' });
    const all = [...out, ...err].join('\n');
    expect(all).not.toContain(SECRET);
  });

  it('forwards ONLY after a successful verify', async () => {
    // Spin up a second loopback server to receive the relay.
    const received: Array<{ body: string; sig?: string }> = [];
    const app = http.createServer((req, res) => {
      const chunks: Buffer[] = [];
      req.on('data', (c) => chunks.push(c));
      req.on('end', () => {
        received.push({
          body: Buffer.concat(chunks).toString('utf8'),
          sig: req.headers['x-nexuspay-signature'] as string | undefined,
        });
        res.statusCode = 200;
        res.end('ok');
      });
    });
    await new Promise<void>((r) => app.listen(0, '127.0.0.1', () => r()));
    const appPort = (app.address() as AddressInfo).port;
    cleanups.push(() => new Promise<void>((r) => app.close(() => r())));

    const { port } = await startListen({ forwardTo: `http://127.0.0.1:${appPort}/hook` });
    const { raw, sig } = signedBody(eventPayload);

    // Bad signature: must NOT forward.
    await post(port, raw, { 'x-nexuspay-signature': 'bad' });
    // Good signature: forwards.
    await post(port, raw, { 'x-nexuspay-signature': sig });

    // Give the async forward a tick to land.
    await new Promise((r) => setTimeout(r, 50));

    expect(received).toHaveLength(1);
    expect(received[0].body).toBe(raw);
    expect(received[0].sig).toBe(sig);
  });

  it('isLoopbackTarget recognizes loopback hosts and rejects others', () => {
    expect(isLoopbackTarget('http://127.0.0.1:3000/x')).toBe(true);
    expect(isLoopbackTarget('http://localhost:3000/x')).toBe(true);
    expect(isLoopbackTarget('http://[::1]:3000/x')).toBe(true);
    expect(isLoopbackTarget('http://example.com/x')).toBe(false);
    expect(isLoopbackTarget('http://10.0.0.5/x')).toBe(false);
    expect(isLoopbackTarget('not a url')).toBe(false);
  });

  it('refuses to forward to a non-loopback target without allowRemote', async () => {
    const { port, err } = await startListen({ forwardTo: 'http://example.com/hook' });
    const { raw, sig } = signedBody(eventPayload);
    await post(port, raw, { 'x-nexuspay-signature': sig });
    await new Promise((r) => setTimeout(r, 30));
    expect(err.join('\n')).toMatch(/non-loopback/i);
  });
});
