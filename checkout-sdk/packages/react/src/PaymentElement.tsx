/**
 * PaymentElement — Mounts the JS PaymentElement into a ref'd div.
 * Renders a loading skeleton while the JS element mounts.
 */

import { useRef, useEffect, useState, type CSSProperties } from 'react';
import {
  PaymentElement as JSPaymentElement,
  type PaymentElementOptions as JSPaymentElementOptions,
  type PaymentMethodType,
} from '@nexuspay/js';
import { useNexusPay } from './useNexusPay';

export interface PaymentElementProps {
  options?: Omit<JSPaymentElementOptions, 'apiBase' | 'sessionToken'>;
  onChange?: (data: { paymentMethod: PaymentMethodType; complete: boolean }) => void;
  onReady?: () => void;
  onError?: (error: { message: string }) => void;
  className?: string;
  style?: CSSProperties;
}

export function PaymentElement({
  options,
  onChange,
  onReady,
  onError,
  className,
  style,
}: PaymentElementProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const elementRef = useRef<JSPaymentElement | null>(null);
  const { nexuspay, session, loading } = useNexusPay();
  const [elementReady, setElementReady] = useState(false);

  useEffect(() => {
    if (!containerRef.current || !nexuspay || !session || loading) return;

    const element = new JSPaymentElement({
      ...options,
      apiBase: (nexuspay as any).httpClient?.baseUrl,
      sessionToken: '', // Set after mount
      allowedMethods: session.allowedPaymentMethods as PaymentMethodType[],
    });

    element.on('ready', () => {
      setElementReady(true);
      onReady?.();
    });

    element.on('change', (data) => {
      onChange?.(data);
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
  }, [nexuspay, session, loading]);

  return (
    <div className={className} style={style}>
      {(loading || !elementReady) && (
        <div
          style={{
            height: '160px',
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
      <style>{`
        @keyframes nxp-react-shimmer {
          from { background-position: 200% 0; }
          to { background-position: -200% 0; }
        }
      `}</style>
    </div>
  );
}
