/**
 * Tests for the `main()` dispatcher in bin/nexuspay.ts — the actual CLI behaviors
 * (not just parseArgs): `--version` PRINTS a resolved semver, top-level `--help`
 * prints usage, and an unknown command / unknown flag returns exit 2 with an
 * `error:` line on stderr. This is the path that regressed once (the version path
 * emitted nothing under CJS per the implementer's Deviation #1) and was previously
 * only smoke-tested.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { main } from '../bin/nexuspay';

function spyStdio() {
  const out: string[] = [];
  const err: string[] = [];
  const o = vi.spyOn(process.stdout, 'write').mockImplementation((s: string | Uint8Array) => {
    out.push(String(s));
    return true;
  });
  const e = vi.spyOn(process.stderr, 'write').mockImplementation((s: string | Uint8Array) => {
    err.push(String(s));
    return true;
  });
  return {
    out,
    err,
    restore: () => {
      o.mockRestore();
      e.mockRestore();
    },
  };
}

describe('main() dispatch', () => {
  let io: ReturnType<typeof spyStdio>;
  beforeEach(() => {
    io = spyStdio();
  });

  it('--version prints a semver-looking string and returns 0', async () => {
    const code = await main(['--version']);
    io.restore();
    expect(code).toBe(0);
    // A real version (not the '0.0.0' fallback would also match, but the built
    // package.json resolves to a non-zero semver). Assert it looks like x.y.z.
    expect(io.out.join('')).toMatch(/\d+\.\d+\.\d+/);
  });

  it('top-level --help prints usage and returns 0', async () => {
    const code = await main(['--help']);
    io.restore();
    expect(code).toBe(0);
    expect(io.out.join('')).toMatch(/Usage: nexuspay <command>/);
  });

  it('an unknown command returns 2 with an error on stderr', async () => {
    const code = await main(['bogus']);
    io.restore();
    expect(code).toBe(2);
    expect(io.err.join('')).toMatch(/error:/);
    expect(io.err.join('')).toMatch(/unknown command/i);
  });

  it('an unknown flag returns 2 with an error on stderr', async () => {
    const code = await main(['trigger', 'payment.succeeded', '--nope']);
    io.restore();
    expect(code).toBe(2);
    expect(io.err.join('')).toMatch(/error:/);
    expect(io.err.join('')).toMatch(/unknown option/i);
  });

  it('a bare invocation prints usage and returns 2', async () => {
    const code = await main([]);
    io.restore();
    expect(code).toBe(2);
    expect(io.out.join('')).toMatch(/Usage: nexuspay <command>/);
  });
});
