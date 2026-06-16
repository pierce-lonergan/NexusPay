# Commercial Licensing — NexusPay

NexusPay is **source-available** under the [PolyForm Noncommercial License 1.0.0](LICENSE).
That license lets you use, modify, and self-host NexusPay **for noncommercial
purposes** (personal projects, research, education, evaluation, nonprofits, etc.)
at no charge.

**Any commercial use requires a separate commercial license from the licensor.**

> ⚖️ This document is a plain-language summary, not the contract itself, and is
> **not legal advice**. The binding terms are the [LICENSE](LICENSE) for
> noncommercial use, and a signed commercial agreement for commercial use.

---

## What counts as "commercial use"?

Broadly, any use **in or by a for-profit organization**, or any use **directed
toward commercial advantage or monetary compensation**. Examples that require a
commercial license:

- Running NexusPay (in production or otherwise) to process, route, or settle
  payments for a business.
- Embedding NexusPay in a product or service you sell, host, or monetize.
- Internal use by a for-profit company to support its commercial operations.
- Offering NexusPay (or a derivative) to third parties as a hosted/managed service.

Examples that **do not** require a commercial license (permitted under PolyForm
Noncommercial): personal/hobby projects, academic research and teaching,
evaluation and testing, and use by qualifying nonprofit/government/educational
organizations as described in the LICENSE.

If you are unsure which category you fall into, **ask** — see contact below.

---

## How to get a commercial license

Commercial licenses are available directly from the maintainer and can be
structured as a flat fee, a subscription, and/or **royalties**, depending on your
use case, scale, and deployment model.

**Contact:** Pierce Lonergan — <lonerganpierce@gmail.com>
**Subject:** `NexusPay commercial license`

Please include: your organization, intended use (self-host vs. embed vs. resell),
expected transaction/processing volume, and deployment timeline. We'll respond
with terms.

---

## Scope notes

- **Client SDKs are MIT.** The browser checkout SDK packages under
  `checkout-sdk/packages/*` (`@nexuspay/js`, `@nexuspay/react`) are licensed under
  the permissive MIT License (see each package's `package.json`) so they can be
  embedded freely in any front-end. This does **not** grant commercial rights to
  the NexusPay platform/server — running the server commercially still requires a
  commercial license.
- **Dependencies keep their own licenses.** NexusPay is built on third-party
  open-source libraries (Spring, etc.), each governed by its own (permissive)
  license; this commercial license covers NexusPay's own code, not its
  dependencies.
- **No warranty.** Commercial or not, the software is provided "as is" to the
  extent permitted by the applicable license / agreement.

---

*Want to fund ongoing development instead of (or in addition to) licensing? See
[Sponsors](https://github.com/sponsors/pierce-lonergan) — `.github/FUNDING.yml`.*
