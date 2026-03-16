/**
 * NexusPayProvider — React context provider for NexusPay SDK.
 * Creates and memoizes NexusPay instance. Provides via context.
 * Loads session on mount.
 */

import {
  createContext,
  useContext,
  useMemo,
  useState,
  useEffect,
  type ReactNode,
} from 'react';
import {
  NexusPay,
  type NexusPayOptions,
  type PaymentSessionResult,
  type NexusPayError,
} from '@nexuspay/js';

export interface NexusPayContextValue {
  nexuspay: NexusPay | null;
  session: PaymentSessionResult | null;
  error: NexusPayError | null;
  loading: boolean;
}

const NexusPayContext = createContext<NexusPayContextValue>({
  nexuspay: null,
  session: null,
  error: null,
  loading: true,
});

export interface NexusPayProviderProps {
  publishableKey: string;
  clientSecret: string;
  options?: NexusPayOptions;
  children: ReactNode;
}

export function NexusPayProvider({
  publishableKey,
  clientSecret,
  options,
  children,
}: NexusPayProviderProps) {
  const nexuspay = useMemo(
    () => new NexusPay(publishableKey, options),
    [publishableKey, options?.apiBase, options?.locale, options?.debug],
  );

  const [session, setSession] = useState<PaymentSessionResult | null>(null);
  const [error, setError] = useState<NexusPayError | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    setLoading(true);
    setError(null);

    nexuspay
      .loadSession(clientSecret)
      .then((result) => {
        if (!cancelled) {
          setSession(result);
          setLoading(false);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err as NexusPayError);
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [nexuspay, clientSecret]);

  const value = useMemo(
    () => ({ nexuspay, session, error, loading }),
    [nexuspay, session, error, loading],
  );

  return (
    <NexusPayContext.Provider value={value}>
      {children}
    </NexusPayContext.Provider>
  );
}

export function useNexusPayContext(): NexusPayContextValue {
  return useContext(NexusPayContext);
}

export { NexusPayContext };
