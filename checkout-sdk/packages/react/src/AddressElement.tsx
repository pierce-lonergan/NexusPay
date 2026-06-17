/**
 * AddressElement — Mounts the AddressElement.
 * Props: mode (billing/shipping), defaultValues, onChange, className, style.
 */

import { useRef, useEffect, useState, type CSSProperties } from 'react';
import {
  AddressElement as JSAddressElement,
  type AddressElementOptions as JSAddressElementOptions,
  type AddressData,
} from '@nexus-pay/js';
import { useNexusPay } from './useNexusPay';

export interface AddressElementProps {
  mode?: 'billing' | 'shipping';
  defaultValues?: Partial<AddressData>;
  options?: Omit<JSAddressElementOptions, 'mode' | 'defaultValues'>;
  onChange?: (data: { value: AddressData; complete: boolean }) => void;
  onReady?: () => void;
  className?: string;
  style?: CSSProperties;
}

export function AddressElement({
  mode = 'billing',
  defaultValues,
  options,
  onChange,
  onReady,
  className,
  style,
}: AddressElementProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const elementRef = useRef<JSAddressElement | null>(null);
  const { loading } = useNexusPay();
  const [elementReady, setElementReady] = useState(false);

  useEffect(() => {
    if (!containerRef.current || loading) return;

    const element = new JSAddressElement({
      ...options,
      mode,
      defaultValues,
    });

    element.on('ready', () => {
      setElementReady(true);
      onReady?.();
    });

    element.on('change', (data) => {
      onChange?.(data);
    });

    element.mount(containerRef.current);
    elementRef.current = element;

    return () => {
      element.destroy();
      elementRef.current = null;
      setElementReady(false);
    };
  }, [loading, mode]);

  return (
    <div className={className} style={style}>
      {(loading || !elementReady) && (
        <div
          style={{
            height: '300px',
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
