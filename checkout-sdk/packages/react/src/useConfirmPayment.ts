/**
 * useConfirmPayment — Hook that confirms payment and handles 3DS challenges.
 */

import { useState, useCallback } from 'react';
import { ChallengeHandler, type ConfirmResult, type NexusPayError } from '@nexus-pay/js';
import { useNexusPay } from './useNexusPay';

export interface ConfirmPaymentState {
  confirming: boolean;
  result: ConfirmResult | null;
  error: NexusPayError | null;
}

export interface UseConfirmPaymentReturn extends ConfirmPaymentState {
  confirmPayment: (paymentTokenId: string) => Promise<ConfirmResult>;
  reset: () => void;
}

export function useConfirmPayment(): UseConfirmPaymentReturn {
  const { nexuspay } = useNexusPay();
  const [state, setState] = useState<ConfirmPaymentState>({
    confirming: false,
    result: null,
    error: null,
  });

  const confirmPayment = useCallback(
    async (paymentTokenId: string): Promise<ConfirmResult> => {
      if (!nexuspay) {
        const error: NexusPayError = {
          type: 'session_error',
          code: 'no_session',
          message: 'NexusPay is not initialized',
        };
        setState({ confirming: false, result: null, error });
        throw error;
      }

      setState({ confirming: true, result: null, error: null });

      try {
        const result = await nexuspay.confirm(paymentTokenId);

        // Handle 3DS challenge if needed
        if (result.status === 'requires_action' && result.nextAction) {
          const challengeHandler = new ChallengeHandler();
          const threeDSResult = await challengeHandler.handle(result.nextAction);

          if (threeDSResult.status === 'succeeded') {
            // Re-confirm after successful 3DS
            const finalResult = await nexuspay.confirm(paymentTokenId);
            setState({ confirming: false, result: finalResult, error: null });
            return finalResult;
          } else {
            const error: NexusPayError = {
              type: 'authentication_error',
              code: 'three_ds_failed',
              message: threeDSResult.error ?? '3DS verification failed',
            };
            setState({ confirming: false, result: null, error });
            throw error;
          }
        }

        setState({ confirming: false, result, error: null });
        return result;
      } catch (err) {
        const error = err as NexusPayError;
        setState({ confirming: false, result: null, error });
        throw error;
      }
    },
    [nexuspay],
  );

  const reset = useCallback(() => {
    setState({ confirming: false, result: null, error: null });
  }, []);

  return { ...state, confirmPayment, reset };
}
