# @nexuspay/js

PCI-compliant card tokenization and payment elements for the browser. Card data
is entered inside a sandboxed iframe and tokenized before it ever touches your
page, so your site stays out of PCI scope.

## Install

```bash
npm install @nexuspay/js
```

Or load it straight from a CDN — the IIFE build exposes a global `NexusPay`:

```html
<script src="https://unpkg.com/@nexuspay/js"></script>
<!-- or: https://cdn.jsdelivr.net/npm/@nexuspay/js -->
<script>
  const nexus = new NexusPay.NexusPay('pk_test_...');
</script>
```

## Usage

Create an instance with your **publishable** key, load the checkout session with
the `client_secret` from your server, then mount a `CardElement` and tokenize:

```ts
import { NexusPay, CardElement } from '@nexuspay/js';

// 1. Init with the publishable key (safe to ship to the browser).
const nexus = new NexusPay('pk_test_...');

// 2. Load the session — `clientSecret` comes from your server
//    (created via @nexuspay/node). It IS the session JWT.
await nexus.loadSession(clientSecret);

// 3. Mount a PCI-safe card input into a container element.
const card = new CardElement();
card.mount('#card-container'); // selector or HTMLElement

card.on('change', (e) => {
  // e.brand, e.complete, etc.
});

// 4. Tokenize, then confirm the payment.
async function pay() {
  // `id` is the payment-token id that confirm() consumes.
  const { id } = await nexus.tokenize({ type: 'card' });
  const result = await nexus.confirm(id);
  console.log(result.status); // 'succeeded' | 'requires_action' | ...
}
```

The SDK also ships `PaymentElement`, `AddressElement`, Apple Pay / Google Pay /
BNPL handlers, 3DS challenge handling, and theming helpers. See the source
exports for the full surface.

## License

MIT © Pierce Lonergan. Part of the
[NexusPay](https://github.com/pierce-lonergan/NexusPay) project.
