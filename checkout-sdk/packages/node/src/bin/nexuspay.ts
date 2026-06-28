/**
 * `nexuspay` CLI entry point.
 *
 * A thin, dependency-free dispatcher over the `trigger` / `listen` subcommands.
 * Hand-rolled arg parsing (no commander/yargs — charter zero-runtime-dep rule).
 *
 * Output format = CJS `.cjs` (the package is `"type":"module"`, so a `.cjs`
 * shebang script is unambiguously CommonJS). The `#!/usr/bin/env node` shebang
 * is injected by tsup's `banner.js` (see tsup.config.ts) — it is intentionally
 * NOT in this source file, so the built artifact carries exactly ONE shebang.
 *
 * Errors print ONLY `err.message` (never the API key or webhook secret) to
 * stderr with a non-zero exit code.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseArgs, assertKnownCommand, ArgError } from './args';
import { runTrigger, TRIGGER_USAGE } from './trigger';
import { runListen, LISTEN_USAGE } from './listen';

const TOP_USAGE = `nexuspay — local webhook test tooling for @nexus-pay/node

Usage: nexuspay <command> [options]

Commands:
  trigger <event_type>    Fire a TEST-MODE webhook (POST /v1/test/events)
  listen                  Receive + verify webhooks locally (loopback only)

Options:
  -h, --help              Show help
  -v, --version           Show version

Run \`nexuspay <command> --help\` for command-specific options.

A Stripe-CLI-style local loop: run \`nexuspay listen --forward-to <app>\` in one
terminal and \`nexuspay trigger payment.succeeded\` in another.`;

/**
 * Reads the package version from package.json. The bin is built as CJS
 * (`dist/bin/nexuspay.cjs`), so `__dirname` is available at runtime; the package
 * root is two levels up. Tries `../../package.json` (built layout) then
 * `../package.json` as a fallback. Read via fs (not `require`) so esbuild does
 * not inline the file at build time.
 */
function readVersion(): string {
  // `__dirname` is injected by the CJS runtime; declared so TS compiles it.
  const dir = typeof __dirname !== 'undefined' ? __dirname : '.';
  for (const rel of ['../../package.json', '../package.json']) {
    try {
      const pkg = JSON.parse(readFileSync(join(dir, rel), 'utf8')) as { version?: string };
      if (pkg && typeof pkg.version === 'string') return pkg.version;
    } catch {
      // try next
    }
  }
  return '0.0.0';
}

async function main(argv: string[]): Promise<number> {
  let parsed;
  try {
    parsed = parseArgs(argv);
  } catch (err) {
    if (err instanceof ArgError) {
      process.stderr.write(`error: ${err.message}\n`);
      return 2;
    }
    throw err;
  }

  // Top-level --version short-circuits (only when no command, else it's an
  // unknown flag for that command, already rejected by parseArgs).
  if (parsed.version && parsed.command === undefined) {
    process.stdout.write(readVersion() + '\n');
    return 0;
  }

  // Top-level --help with no command.
  if (parsed.help && parsed.command === undefined) {
    process.stdout.write(TOP_USAGE + '\n');
    return 0;
  }

  if (parsed.command === undefined) {
    process.stdout.write(TOP_USAGE + '\n');
    return parsed.help || parsed.version ? 0 : 2;
  }

  try {
    assertKnownCommand(parsed);
  } catch (err) {
    if (err instanceof ArgError) {
      process.stderr.write(`error: ${err.message}\n`);
      return 2;
    }
    throw err;
  }

  switch (parsed.command) {
    case 'trigger':
      return await runTrigger(parsed);
    case 'listen':
      return await runListen(parsed);
    default:
      // Unreachable (assertKnownCommand guards), but be explicit.
      process.stderr.write(`error: unknown command: ${parsed.command}\n`);
      return 2;
  }
}

// Surface usage strings for help dispatch consistency / testing.
export { TOP_USAGE, TRIGGER_USAGE, LISTEN_USAGE, main };

/**
 * Only auto-run when executed as the CLI entry (the built `dist/bin/nexuspay.cjs`
 * is CJS, so `require.main === module` holds at runtime). When IMPORTED by a test
 * (`import { main }`), this guard is false, so importing the module does NOT
 * invoke main, set process.exitCode, or write to stdout/stderr as a side effect.
 */
function isCliEntry(): boolean {
  try {
    // `require`/`module` exist in the built CJS artifact, not under ESM test transform.
    return typeof require !== 'undefined' && typeof module !== 'undefined' && require.main === module;
  } catch {
    return false;
  }
}

if (isCliEntry()) {
  main(process.argv.slice(2))
    .then((code) => {
      process.exitCode = code;
    })
    .catch((err) => {
      // Last-resort handler: print the message ONLY (never any secret/key).
      const msg = err instanceof Error ? err.message : String(err);
      process.stderr.write(`error: ${msg}\n`);
      process.exitCode = 1;
    });
}
