/**
 * useNexusPay — Hook that returns { nexuspay, session, error, loading } from context.
 */

import { useNexusPayContext, type NexusPayContextValue } from './NexusPayProvider';

export function useNexusPay(): NexusPayContextValue {
  const context = useNexusPayContext();

  if (!context.nexuspay && !context.loading) {
    console.warn(
      '[NexusPay] useNexusPay must be used within a <NexusPayProvider>',
    );
  }

  return context;
}
