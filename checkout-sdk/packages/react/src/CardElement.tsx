/**
 * CardElement — Mounts the CardElement (iframe-isolated) into a ref'd div.
 * Props: options, onChange, onReady, onFocus, onBlur, className, style.
 */

import { useRef, useEffect, useState, type CSSProperties } from 'react';
import {
  CardElement as JSCardElement,
  type CardElementOptions as JSCardElementOptions,
  type CardChangePayload,
} from '@nexuspay/js';
import { useNexusPay } from './useNexusPay';

export interface CardElementProps {
  options?: Omit<JSCardElementOptions, 'apiBase' | 'sessionToken'>;
  onChange?: (data: CardChangePayload) => void;
  onReady?: () => void;
  onFocus?: () => void;
  onBlur?: () => void;
  onError?: (error: { message: string }) => void;
  className?: string;
  style?: CSSProperties;
}

export function CardElement({
  options,
  onChange,
  onReady,
  onFocus,
  onBlur,
  onError,
  className,
  style,
}: CardElementProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const elementRef = useRef<JSCardElement | null>(null);
  const { nexuspay, loading } = useNexusPay();
  const [elementReady, setElementReady] = useState(false);

  useEffect(() => {
    if (!containerRef.current || !nexuspay || loading) return;

    const element = new JSCardElement({
      ...options,
      apiBase: (nexuspay as any).httpClient?.baseUrl,
      sessionToken: '',
    });

    element.on('ready', () => {
      setElementReady(true);
      onReady?.();
    });

    element.on('change', (data) => {
      onChange?.(data);
    });

    element.on('focus', () => {
      onFocus?.();
    });

    element.on('blur', () => {
      onBlur?.();
    });

    element.on('error', (error) => {
      onError?.(error);
    });

    element.mount(containerRef.current);
    elementRef.current = element;

    return () => {
      element.destroy();
      elementRef.current = null;
      setElementReady(false);
    };
  }, [nexuspay, loading]);

  return (
    <div className={className} style={style} data-nexuspay-card-wrapper="">
      {(loading || !elementReady) && (
        <div
          style={{
            height: '120px',
            borderRadius: '8px',
            background: 'linear-gradient(90deg, #E5E7EB 25%, #F3F4F6 50%, #E5E7EB 75%)',
            backgroundSize: '200% 100%',
            animation: 'nxp-react-shimmer 1.5s infinite linear',
          }}
          aria-hidden="true"
        />
      )}
      <div
        ref={containerRef}
        style={{ display: loading || !elementReady ? 'none' : 'block' }}
      />
    </div>
  );
}
