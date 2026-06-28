import { describe, it, expect } from 'vitest';
import { parseArgs, assertKnownCommand, ArgError } from '../bin/args';

describe('parseArgs', () => {
  it('dispatches the trigger subcommand with a positional', () => {
    const p = parseArgs(['trigger', 'payment.succeeded']);
    expect(p.command).toBe('trigger');
    expect(p.positionals).toEqual(['payment.succeeded']);
  });

  it('dispatches the listen subcommand', () => {
    const p = parseArgs(['listen']);
    expect(p.command).toBe('listen');
  });

  it('parses --flag value form', () => {
    const p = parseArgs(['trigger', 'payment.succeeded', '--key', 'sk_test_x']);
    expect(p.flags.key).toBe('sk_test_x');
  });

  it('parses --flag=value form', () => {
    const p = parseArgs(['trigger', 'payment.succeeded', '--base-url=http://localhost:8090']);
    expect(p.flags['base-url']).toBe('http://localhost:8090');
  });

  it('parses boolean flags (no value)', () => {
    const p = parseArgs(['listen', '--allow-remote']);
    expect(p.flags['allow-remote']).toBe(true);
  });

  it('recognizes top-level --help and -h', () => {
    expect(parseArgs(['--help']).help).toBe(true);
    expect(parseArgs(['-h']).help).toBe(true);
  });

  it('recognizes top-level --version and -v', () => {
    expect(parseArgs(['--version']).version).toBe(true);
    expect(parseArgs(['-v']).version).toBe(true);
  });

  it('recognizes per-command --help', () => {
    const p = parseArgs(['trigger', '--help']);
    expect(p.command).toBe('trigger');
    expect(p.help).toBe(true);
  });

  it('rejects an unknown flag for a command', () => {
    expect(() => parseArgs(['trigger', 'payment.succeeded', '--nope'])).toThrow(ArgError);
    expect(() => parseArgs(['trigger', 'payment.succeeded', '--nope'])).toThrow(/unknown option/);
  });

  it('rejects a value flag with no value', () => {
    expect(() => parseArgs(['trigger', 'x', '--key'])).toThrow(/requires a value/);
  });

  it('rejects a value on a boolean flag', () => {
    expect(() => parseArgs(['listen', '--allow-remote=yes'])).toThrow(/does not take a value/);
  });

  it('rejects a top-level flag other than help/version', () => {
    expect(() => parseArgs(['--bogus'])).toThrow(/unknown option/);
  });

  it('rejects an unknown short flag', () => {
    expect(() => parseArgs(['trigger', 'x', '-z'])).toThrow(/unknown option/);
  });
});

describe('assertKnownCommand', () => {
  it('passes for known commands', () => {
    expect(() => assertKnownCommand(parseArgs(['trigger', 'x']))).not.toThrow();
    expect(() => assertKnownCommand(parseArgs(['listen']))).not.toThrow();
  });

  it('throws on an unknown command', () => {
    expect(() => assertKnownCommand(parseArgs(['bogus']))).toThrow(ArgError);
    expect(() => assertKnownCommand(parseArgs(['bogus']))).toThrow(/unknown command/);
  });

  it('allows a missing command (top-level)', () => {
    expect(() => assertKnownCommand(parseArgs([]))).not.toThrow();
  });
});
