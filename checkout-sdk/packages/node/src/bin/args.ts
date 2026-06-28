/**
 * Tiny, dependency-free arg parser for the `nexuspay` CLI.
 *
 * Pure functions so vitest can assert parsing + unknown-flag / unknown-command
 * behavior directly, without spawning a process. No commander/yargs (charter
 * zero-runtime-dep rule).
 *
 * Supported forms:
 *   nexuspay <command> [positionals] [--flag value] [--flag=value] [--bool]
 *   nexuspay --help | -h        (top-level)
 *   nexuspay --version | -v      (top-level)
 *
 * Boolean flags take no value; value flags accept either `--flag value` or
 * `--flag=value`. An UNKNOWN flag (for the resolved command) is rejected so a
 * typo'd option fails fast instead of being silently ignored.
 */

/** A parse failure that the CLI surfaces to stderr + a non-zero exit. */
export class ArgError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ArgError';
  }
}

export interface ParsedArgs {
  /** The subcommand (`trigger` / `listen`), or undefined for a bare/top-level invocation. */
  command?: string;
  /** Non-flag positionals after the command (e.g. the event_type). */
  positionals: string[];
  /** Parsed flags. Booleans are `true`; value flags are strings. */
  flags: Record<string, string | boolean>;
  /** True if `--help`/`-h` appeared anywhere. */
  help: boolean;
  /** True if `--version`/`-v` appeared anywhere. */
  version: boolean;
}

/** Known subcommands. Anything else -> unknown-command error. */
export const COMMANDS = ['trigger', 'listen'] as const;
export type Command = (typeof COMMANDS)[number];

/**
 * Known flags per command (long names without the leading `--`). Boolean flags
 * are listed in `booleans`; everything else is a value flag. `-h`/`-v` are
 * handled specially (help/version) before per-command validation.
 */
interface FlagSpec {
  value: string[];
  booleans: string[];
}

const FLAG_SPECS: Record<Command, FlagSpec> = {
  trigger: {
    value: ['key', 'base-url', 'id', 'data'],
    booleans: [],
  },
  listen: {
    value: ['port', 'secret', 'forward-to'],
    booleans: ['allow-remote'],
  },
};

/** Returns the flag spec for a command, or undefined for top-level. */
function specFor(command: string | undefined): FlagSpec | undefined {
  if (command && (COMMANDS as readonly string[]).includes(command)) {
    return FLAG_SPECS[command as Command];
  }
  return undefined;
}

/**
 * Parses argv (already sliced of `node script`, i.e. just the user args).
 *
 * Resolution order:
 *  1. The FIRST non-flag token is the command (if it is a known command), else
 *     it stays an "unknown command" candidate we reject (unless --help/--version
 *     short-circuits at the top level).
 *  2. Remaining tokens are flags/positionals validated against the command's spec.
 */
export function parseArgs(argv: string[]): ParsedArgs {
  const out: ParsedArgs = {
    command: undefined,
    positionals: [],
    flags: {},
    help: false,
    version: false,
  };

  // First pass: pull the command (first bare token) so we know which flag spec
  // governs the rest. --help/--version are recognized regardless of position.
  let command: string | undefined;
  let commandSeen = false;
  const rest: string[] = [];
  for (const tok of argv) {
    if (!commandSeen && !tok.startsWith('-')) {
      command = tok;
      commandSeen = true;
      continue;
    }
    rest.push(tok);
  }
  out.command = command;

  const spec = specFor(command);

  for (let i = 0; i < rest.length; i++) {
    const tok = rest[i];

    if (tok === '--help' || tok === '-h') {
      out.help = true;
      continue;
    }
    if (tok === '--version' || tok === '-v') {
      out.version = true;
      continue;
    }

    if (tok.startsWith('--')) {
      // --flag=value or --flag value
      let name: string;
      let inlineValue: string | undefined;
      const eq = tok.indexOf('=');
      if (eq !== -1) {
        name = tok.slice(2, eq);
        inlineValue = tok.slice(eq + 1);
      } else {
        name = tok.slice(2);
      }

      if (!spec) {
        // Top-level (no/unknown command) with a flag other than help/version.
        throw new ArgError(`unknown option: --${name}`);
      }

      if (spec.booleans.includes(name)) {
        if (inlineValue !== undefined) {
          throw new ArgError(`option --${name} does not take a value`);
        }
        out.flags[name] = true;
        continue;
      }

      if (spec.value.includes(name)) {
        let value = inlineValue;
        if (value === undefined) {
          value = rest[i + 1];
          if (value === undefined || value.startsWith('-')) {
            throw new ArgError(`option --${name} requires a value`);
          }
          i++;
        }
        out.flags[name] = value;
        continue;
      }

      throw new ArgError(`unknown option: --${name}`);
    }

    if (tok.startsWith('-') && tok.length > 1) {
      // A short flag other than -h/-v is unknown.
      throw new ArgError(`unknown option: ${tok}`);
    }

    out.positionals.push(tok);
  }

  return out;
}

/**
 * Validates the resolved command AFTER a top-level help/version short-circuit
 * has been ruled out. Throws ArgError on an unknown command. A missing command
 * is allowed here (the caller prints top-level help).
 */
export function assertKnownCommand(parsed: ParsedArgs): void {
  if (parsed.command === undefined) return;
  if (!(COMMANDS as readonly string[]).includes(parsed.command)) {
    throw new ArgError(
      `unknown command: ${parsed.command} (expected one of: ${COMMANDS.join(', ')})`,
    );
  }
}
