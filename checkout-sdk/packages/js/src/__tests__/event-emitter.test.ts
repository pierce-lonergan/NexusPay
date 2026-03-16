import { describe, it, expect, vi } from 'vitest';
import { EventEmitter } from '../event-emitter';

describe('EventEmitter', () => {
  it('calls handler on emit', () => {
    const emitter = new EventEmitter<{ test: string }>();
    const handler = vi.fn();
    emitter.on('test', handler);
    emitter.emit('test', 'hello');
    expect(handler).toHaveBeenCalledWith('hello');
  });

  it('supports multiple handlers', () => {
    const emitter = new EventEmitter<{ test: number }>();
    const h1 = vi.fn();
    const h2 = vi.fn();
    emitter.on('test', h1);
    emitter.on('test', h2);
    emitter.emit('test', 42);
    expect(h1).toHaveBeenCalledWith(42);
    expect(h2).toHaveBeenCalledWith(42);
  });

  it('removes handler with off', () => {
    const emitter = new EventEmitter<{ test: string }>();
    const handler = vi.fn();
    emitter.on('test', handler);
    emitter.off('test', handler);
    emitter.emit('test', 'hello');
    expect(handler).not.toHaveBeenCalled();
  });

  it('once fires only once', () => {
    const emitter = new EventEmitter<{ test: string }>();
    const handler = vi.fn();
    emitter.once('test', handler);
    emitter.emit('test', 'first');
    emitter.emit('test', 'second');
    expect(handler).toHaveBeenCalledTimes(1);
    expect(handler).toHaveBeenCalledWith('first');
  });

  it('removeAllListeners clears specific event', () => {
    const emitter = new EventEmitter<{ a: string; b: string }>();
    const ha = vi.fn();
    const hb = vi.fn();
    emitter.on('a', ha);
    emitter.on('b', hb);
    emitter.removeAllListeners('a');
    emitter.emit('a', 'x');
    emitter.emit('b', 'y');
    expect(ha).not.toHaveBeenCalled();
    expect(hb).toHaveBeenCalledWith('y');
  });

  it('does not throw on emit with no handlers', () => {
    const emitter = new EventEmitter<{ test: string }>();
    expect(() => emitter.emit('test', 'hello')).not.toThrow();
  });

  it('catches handler errors without breaking other handlers', () => {
    const emitter = new EventEmitter<{ test: string }>();
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const badHandler = () => { throw new Error('oops'); };
    const goodHandler = vi.fn();
    emitter.on('test', badHandler);
    emitter.on('test', goodHandler);
    emitter.emit('test', 'data');
    expect(goodHandler).toHaveBeenCalledWith('data');
    expect(errorSpy).toHaveBeenCalled();
    errorSpy.mockRestore();
  });
});
