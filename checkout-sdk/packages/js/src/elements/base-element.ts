/**
 * Abstract base class for all NexusPay UI elements.
 * Provides mount/unmount lifecycle, event handling, loading skeleton,
 * and appearance management.
 */

import { EventEmitter } from '../event-emitter';
import type { Appearance, ElementOptions } from '../types';
import { injectThemeStyles } from '../theme/css-properties';

export interface BaseElementEvents {
  [key: string]: unknown;
  ready: void;
  error: { message: string };
  focus: void;
  blur: void;
}

export abstract class BaseElement<
  TEvents extends BaseElementEvents = BaseElementEvents,
> extends EventEmitter<TEvents> {
  protected container: HTMLElement | null = null;
  protected wrapper: HTMLElement | null = null;
  protected appearance: Appearance;
  protected classes: Record<string, string>;
  private mounted = false;

  constructor(options?: ElementOptions) {
    super();
    this.appearance = options?.appearance ?? {};
    this.classes = options?.classes ?? {};
  }

  /**
   * Mounts the element into the specified container.
   * Shows a loading skeleton during initialization.
   */
  mount(container: string | HTMLElement): void {
    if (this.mounted) {
      console.warn('[NexusPay] Element is already mounted. Call unmount() first.');
      return;
    }

    const target =
      typeof container === 'string'
        ? document.querySelector<HTMLElement>(container)
        : container;

    if (!target) {
      throw new Error(`[NexusPay] Container not found: ${container}`);
    }

    this.container = target;

    // Create wrapper
    this.wrapper = document.createElement('div');
    this.wrapper.setAttribute('data-nexuspay-element', this.elementType());
    this.wrapper.style.position = 'relative';

    // Apply custom classes
    if (this.classes.base) {
      this.wrapper.className = this.classes.base;
    }

    // Inject theme styles
    injectThemeStyles(this.appearance, this.wrapper);

    // Show loading skeleton
    this.showSkeleton();

    this.container.appendChild(this.wrapper);
    this.mounted = true;

    // Subclass renders content (async allowed)
    this.render().then(() => {
      this.hideSkeleton();
      this.emit('ready' as keyof TEvents, undefined as TEvents[keyof TEvents]);
    }).catch((err) => {
      this.hideSkeleton();
      this.emit('error' as keyof TEvents, {
        message: err instanceof Error ? err.message : 'Element failed to load',
      } as TEvents[keyof TEvents]);
    });
  }

  /** Removes the element from the DOM. */
  unmount(): void {
    if (!this.mounted || !this.wrapper || !this.container) return;
    this.cleanup();
    this.container.removeChild(this.wrapper);
    this.wrapper = null;
    this.container = null;
    this.mounted = false;
  }

  /** Updates element options (appearance, classes). */
  update(options: ElementOptions): void {
    if (options.appearance) {
      this.appearance = options.appearance;
      if (this.wrapper) {
        injectThemeStyles(this.appearance, this.wrapper);
      }
    }
    if (options.classes) {
      this.classes = options.classes;
    }
  }

  /** Tears down the element completely. */
  destroy(): void {
    this.unmount();
    this.removeAllListeners();
  }

  isMounted(): boolean {
    return this.mounted;
  }

  /** Element type identifier, used for data attributes. */
  protected abstract elementType(): string;

  /** Renders element content into this.wrapper. */
  protected abstract render(): Promise<void>;

  /** Clean up resources (iframes, listeners, etc.) before unmount. */
  protected abstract cleanup(): void;

  // --- Skeleton ---

  private skeletonEl: HTMLElement | null = null;

  protected showSkeleton(): void {
    if (!this.wrapper) return;
    this.skeletonEl = document.createElement('div');
    this.skeletonEl.className = 'Skeleton';
    this.skeletonEl.style.height = '44px';
    this.skeletonEl.style.width = '100%';
    this.skeletonEl.setAttribute('aria-hidden', 'true');
    this.wrapper.appendChild(this.skeletonEl);
  }

  protected hideSkeleton(): void {
    if (this.skeletonEl && this.wrapper?.contains(this.skeletonEl)) {
      this.wrapper.removeChild(this.skeletonEl);
    }
    this.skeletonEl = null;
  }
}
