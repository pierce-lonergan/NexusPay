# @nexus-pay/react

React bindings for [NexusPay](https://github.com/pierce-lonergan/NexusPay)
checkout: a context provider, hooks, and ready-to-mount payment elements.

## Install

```bash
npm install @nexus-pay/react @nexus-pay/js react
```

`react` (>=18) and `@nexus-pay/js` are **peer dependencies** — install them
alongside this package.

## Usage

Wrap your checkout in `NexusPayProvider` (pass the publishable key and the
`clientSecret` from your server), drop in a `PaymentElement`, and confirm with
`useConfirmPayment`:

```tsx
import {
  NexusPayProvider,
  PaymentElement,
  useConfirmPayment,
} from '@nexus-pay/react';

function CheckoutForm() {
  const { confirmPayment, confirming, error } = useConfirmPayment();

  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        // paymentTokenId comes from your tokenize step.
        const result = await confirmPayment(paymentTokenId);
        if (result.status === 'succeeded') {
          // ...redirect to your success page
        }
      }}
    >
      <PaymentElement />
      <button type="submit" disabled={confirming}>
        Pay
      </button>
      {error && <p role="alert">{error.message}</p>}
    </form>
  );
}

export function Checkout({ clientSecret }: { clientSecret: string }) {
  return (
    <NexusPayProvider publishableKey="pk_test_..." clientSecret={clientSecret}>
      <CheckoutForm />
    </NexusPayProvider>
  );
}
```

Also exported: `CardElement`, `AddressElement`, and the `useNexusPay` hook for
direct access to the underlying `@nexus-pay/js` instance.

## License

MIT © Pierce Lonergan. Part of the
[NexusPay](https://github.com/pierce-lonergan/NexusPay) project.
