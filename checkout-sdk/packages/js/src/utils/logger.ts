/**
 * Conditional console logger for NexusPay SDK.
 * Only logs when debug mode is enabled.
 */

let debugEnabled = false;

export function setDebug(enabled: boolean): void {
  debugEnabled = enabled;
}

export function isDebug(): boolean {
  return debugEnabled;
}

export function debug(message: string, ...args: unknown[]): void {
  if (debugEnabled) {
    console.log(`[NexusPay] ${message}`, ...args);
  }
}

export function warn(message: string, ...args: unknown[]): void {
  console.warn(`[NexusPay] ${message}`, ...args);
}

export function error(message: string, ...args: unknown[]): void {
  console.error(`[NexusPay] ${message}`, ...args);
}
