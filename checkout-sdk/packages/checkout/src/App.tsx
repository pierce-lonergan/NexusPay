/**
 * App — reads session_id + publishable_key from URL, wraps in NexusPayProvider.
 */

import { NexusPayProvider } from '@nexus-pay/react';
import { CheckoutPage } from './CheckoutPage';

export function App() {
  const params = new URLSearchParams(window.location.search);
  const publishableKey = params.get('pk') ?? '';
  const clientSecret = params.get('cs') ?? '';
  const apiBase = params.get('api_base') ?? undefined;

  if (!publishableKey || !clientSecret) {
    return (
      <div className="checkout-error">
        <h1>Invalid Checkout Link</h1>
        <p>Missing required parameters. Please use the checkout link provided by the merchant.</p>
      </div>
    );
  }

  return (
    <NexusPayProvider
      publishableKey={publishableKey}
      clientSecret={clientSecret}
      options={{ apiBase }}
    >
      <CheckoutPage />
    </NexusPayProvider>
  );
}
