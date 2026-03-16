/**
 * Typed event emitter for NexusPay SDK events.
 * Zero dependencies — uses native Map and Set.
 */

export type EventHandler<T = unknown> = (data: T) => void;

export class EventEmitter<EventMap extends Record<string, unknown> = Record<string, unknown>> {
  private listeners = new Map<keyof EventMap, Set<EventHandler<any>>>();

  on<K extends keyof EventMap>(event: K, handler: EventHandler<EventMap[K]>): this {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set());
    }
    this.listeners.get(event)!.add(handler);
    return this;
  }

  off<K extends keyof EventMap>(event: K, handler: EventHandler<EventMap[K]>): this {
    this.listeners.get(event)?.delete(handler);
    return this;
  }

  once<K extends keyof EventMap>(event: K, handler: EventHandler<EventMap[K]>): this {
    const wrapper: EventHandler<EventMap[K]> = (data) => {
      this.off(event, wrapper);
      handler(data);
    };
    return this.on(event, wrapper);
  }

  emit<K extends keyof EventMap>(event: K, data: EventMap[K]): void {
    const handlers = this.listeners.get(event);
    if (handlers) {
      for (const handler of handlers) {
        try {
          handler(data);
        } catch (err) {
          console.error(`[NexusPay] Error in ${String(event)} handler:`, err);
        }
      }
    }
  }

  removeAllListeners(event?: keyof EventMap): void {
    if (event) {
      this.listeners.delete(event);
    } else {
      this.listeners.clear();
    }
  }
}
