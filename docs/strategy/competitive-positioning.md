# NexusPay: A Competitive Assessment

*An engineering/analyst evaluation of NexusPay against proprietary and open-source payment systems — with a plain answer to "would you actually adopt it?"*

*Assessment date: 2026-07-01. This document supersedes the earlier (2026-03) positioning draft, which framed NexusPay as an enterprise-platform competitor without the orchestration/reference framing, the license blocker, or the maturity caveats. Repo claims below were verified against the source tree; competitor facts are cited inline with access dates. Where a figure could not be independently sourced, it is stated qualitatively rather than with a false-precision number.*

---

## 1. TL;DR verdict

NexusPay is a genuinely rigorous, security-first **payment-orchestration and reference/ledger layer built on top of HyperSwitch** — not a payment processor, not an acquirer, and not a Stripe/Adyen competitor. Judged against its *actual* peer group (open-source orchestration/ledger/billing: HyperSwitch, Kill Bill, Lago, Formance, Medusa), it stands out on two axes that are real and hard to fake: **unusually strict zero-trust multi-tenancy** and **Stripe-test-mode-class developer experience**. But it is a **solo, v0.x portfolio project** that has never moved real money, carries no external attestation, and — decisively — ships under a **PolyForm Noncommercial license** that forbids the commercial use its target adopters would need. For essentially every for-profit team, the honest recommendation is to use HyperSwitch (or Kill Bill/Lago/Formance) directly; NexusPay is best understood as a *reference architecture and hardened starting scaffold*, not a production dependency you'd pick over the tools it wraps.

---

## 2. What NexusPay actually is

NexusPay does **not hold an acquiring license, does not settle funds, and moves no real money.** In test mode it runs an in-process mock gateway; in production it routes to HyperSwitch, which in turn routes to real processors. Its ledger is a *parallel accounting/operations record*, not the banking source of truth for funds.

That places it firmly in the **orchestration + reference-architecture/ops-ledger tier that sits *above* processors** — the same category boundary as HyperSwitch, Kill Bill, Lago, and Formance, and explicitly *not* the licensed-acquirer tier occupied by Stripe, Adyen, Braintree, Checkout.com, and Square.

Concretely, NexusPay is an opinionated, security-hardened **Spring Modulith** reference application wrapped around HyperSwitch (Apache-2.0), adding a CFO/ops-facing layer:

- a **double-entry ledger** (per-currency zero-sum, FX gain/loss),
- **maker-checker refunds** and **dispute-to-ledger** postings,
- **fraud/sanctions screening** hooks,
- **subscription billing / dunning**,
- **zero-trust multi-tenancy**, and
- a **Stripe-test-mode-class developer harness** (mock gateway, forced outcomes, test clocks, sandbox reset, idempotency-key inspection, typed SDK fixtures, a `nexuspay` CLI).

> **Framing that matters:** "NexusPay vs Stripe on features" is a category error. The incumbents win money movement, external PCI Level 1 attestation, and cross-merchant fraud-ML *by definition*, and NexusPay does not try to occupy that tier. The **one** dimension where a comparison to the incumbents is fair — and where NexusPay deliberately competes — is **developer experience / test tooling**, where Stripe is the explicit gold standard it emulates.

---

## 3. Comparison matrix

| Dimension | **NexusPay** | **Proprietary leaders** (Stripe, Adyen, Braintree, Checkout.com, Square) | **OSS leaders** (HyperSwitch, Kill Bill, Lago, Formance, Medusa) |
|---|---|---|---|
| **What it is** | Orchestration + reference-architecture/ops-ledger layer *wrapping* HyperSwitch. A v0.x Spring Modulith reference app, not a product/service. | Licensed processors/acquirers that *are* the PSP; full-stack money movement + adjacent products (fraud, billing, marketplaces). | HyperSwitch = the actual OSS orchestration engine (Rust); Kill Bill = OSS billing+payments; Formance = OSS ledger; Lago = OSS metering/billing; Medusa = commerce w/ payment module. |
| **Money movement & settlement** | Moves **no real money**; mock gateway in test, routes to HyperSwitch→processors in prod. Never processed a real transaction. | Real settlement and regulatory liability; Adyen and Checkout.com are directly-licensed acquirers ([adyen.com](https://www.adyen.com/), [checkout.com](https://www.checkout.com/); accessed 2026-07-01). | Also **not** acquirers — HyperSwitch/Kill Bill route to real processors; Formance/Lago delegate money movement to PSPs. Same category boundary as NexusPay ([github.com/juspay/hyperswitch](https://github.com/juspay/hyperswitch)). |
| **Security & tenant isolation** | **Genuine delta.** Zero `X-Tenant-Id` trust (tenant-from-principal), per-API-key scopes, HMAC-signed + replay-deduped webhooks, idempotency across capture/void/refund, maker-checker refunds, outbox, SSRF-guarded egress, and **8 cross-tenant/IDOR redteam tests** in the gate. **Caveat:** Postgres RLS is implemented but **dormant** (`NEXUSPAY_RLS_ENFORCE:false`); isolation is application-layer (but *tested*) until a human-gated cutover. | Card data never touches merchant servers; isolation is the vendor's externally-attested problem. Not adopter-auditable, but battle-tested at scale ([docs.stripe.com/security](https://docs.stripe.com/security)). | Mature but do **not** ship NexusPay's opinionated zero-trust tenancy scaffold — with raw HyperSwitch "the surrounding tenant/webhook-security scaffolding is left to you." This is NexusPay's real value-add. |
| **PCI / compliance posture** | **Not** PCI-attested (software can't be — PCI attests an *environment*). PCI-safe design (never-store-raw-PAN, AES-256-GCM, tokenized/hosted flows) can help an *adopter* shrink scope toward SAQ A/A-EP. No SOC 2, no external pentest, no bug bounty ([PCI SAQ reference](https://www.pcisecuritystandards.org/)). | Externally-attested PCI DSS Level 1 Service Providers + SOC 1/2/3, attested by QSAs — not aspirational ([docs.stripe.com/security](https://docs.stripe.com/security)). | HyperSwitch ships a PCI-compliant hosted SDK and inherits a PCI DSS v4.0 posture; adopter still owns environment attestation. Kill Bill/Formance carry no processor attestation ([hyperswitch.io](https://hyperswitch.io/)). |
| **Test-mode / developer experience** | **Genuine strength, ~Stripe-test-mode parity.** In-process mock gateway (`sk_test_` never hits HyperSwitch), forced decline/3DS/processing outcomes, per-tenant test clocks, sandbox reset, idempotency-key inspect, typed SDK fixtures, `nexuspay` CLI — delivered with no security boundary weakened. | Stripe is the DX gold standard (documented test cards, idempotency keys, test clocks, typed SDKs); Braintree/Adyen capable but trail it ([docs.stripe.com/testing](https://docs.stripe.com/testing)). | A weak spot for the field — Kill Bill's test ergonomics are dated relative to Stripe-style sandboxes; Medusa's payment module is a thin abstraction. **NexusPay is arguably ahead of its OSS peers here.** |
| **Feature breadth** (billing/ledger/disputes/payouts) | Broad but **shallow vs specialists**: correct-but-light double-entry ledger (per-currency zero-sum, FX gain/loss); solid subscription reference (5 pricing models, proration, dunning) but **no usage-metering/tax/entitlements**; disputes-to-ledger present; **marketplace/split-payments & payout execution are stubs/deferred.** | Deep first-party breadth: Stripe Billing/Connect/Tax/Issuing/Radar; Adyen unified online+POS+risk+settlement. Turnkey ([stripe.com](https://stripe.com/), [adyen.com](https://www.adyen.com/)). | Specialists out-mature it per lane: Lago (usage metering at high throughput), Kill Bill (decade-proven full billing), Formance / TigerBeetle (programmable / high-throughput ledger of record) — you'd put Formance/TigerBeetle *under* a NexusPay-shaped layer ([getlago.com](https://www.getlago.com/), [github.com/formancehq/ledger](https://github.com/formancehq/ledger), [tigerbeetle.com](https://tigerbeetle.com/)). |
| **Observability / reconciliation depth** | Ships real `analytics/`, `observability/`, and `reconciliation/` modules, but reconciliation is **partial** and unproven at scale — no evidence of large-volume recon against external truth. | Rich vendor dashboards, financial reports, and reconciliation tooling as a first-class product surface (Stripe/Adyen dashboards). | Reconciliation/ledger correctness is a **core differentiator** here — Formance (and, in the proprietary-adjacent space, Modern Treasury) lead on programmable recon; NexusPay's modules exist but are materially shallower. |
| **Extensibility & architecture** | Clean hexagonal **Spring Modulith**: ~16 modules, build-time boundary verification, ports/adapters, transactional outbox + Debezium CDC, resilience4j circuit breaker on the HyperSwitch adapter (making it swappable). Genuinely legible. **But** deep Spring coupling; single shared Flyway schema. | Closed SaaS — extend via API/webhooks only; no source, no self-host, no data-plane ownership; you inherit their ledger model and roadmap ([stripe.com/pricing](https://stripe.com/pricing)). | HyperSwitch (Rust, plugin connectors), Kill Bill (mature plugin ecosystem), Medusa (MIT modular TS) are all extensible and forkable; HyperSwitch is *lower-level* than NexusPay's opinionated full reference app. |
| **Operational burden** | Self-host stack (Postgres/Kafka/Valkey/Keycloak/Temporal + HyperSwitch). A **Helm chart exists** (`nexuspay-helm/`, per-env values), **but production hygiene is thin**: no TLS/mTLS between services, no backup/PITR, no autoscaling/ingress hardening by default, and no on-call runbooks beyond the RLS cutover. Boots in ~5 min locally; production-readiness unproven. | Zero infra/maintenance/bus-factor burden; live in hours; vendor runs everything at high availability ([stripe.com](https://stripe.com/)). | Self-hosting shifts maintenance/upgrades/PCI-scope onto *your* team for all of them; HyperSwitch is operationally heavy to self-host; Kill Bill has a reputation for operational complexity ([killbill.io](https://killbill.io/)). |
| **Maturity / support / bus factor** | **Weakest axis.** Solo portfolio project, **bus factor of one**, v0.x, never real money, no external audit/SOC 2/PCI/bug-bounty, community-only support. Internal rigor is real (extensive tests, adversarial `LESSONS.md`) but is an *engineering standard, not external validation*. | Organizational continuity + 24/7 SRE/support + SLAs; large engineering orgs operating at scale with very high uptime ([stripe.com](https://stripe.com/), [adyen.com](https://www.adyen.com/)). | Real bus factor: HyperSwitch is Juspay-backed and venture-funded with production users; Kill Bill has ~15 years invoicing at scale; Lago is YC-backed with a SOC 2 program; Formance is venture-funded ([juspay.io](https://juspay.io/), [killbill.io](https://killbill.io/), [getlago.com](https://www.getlago.com/), [formance.com](https://www.formance.com/)). |
| **License & cost** | **Decisive blocker: PolyForm Noncommercial 1.0.0** — any for-profit production use needs a separate commercial grant that does not exist today; not OSI-approved; fails most corporate legal intake. No per-tx take-rate (moves no money). Browser SDKs `@nexus-pay/{js,react,node}` are separately **MIT**. | Take-rate, never self-hostable: Stripe/Braintree/Square ~2.6–2.9% + fixed fee; Adyen/Checkout.com interchange++ with a smaller markup, cheaper at volume ([stripe.com/pricing](https://stripe.com/pricing), [adyen.com/pricing](https://www.adyen.com/pricing), [squareup.com](https://squareup.com/)). | All permit **commercial** use, unlike NexusPay: HyperSwitch/Kill Bill **Apache-2.0**, Formance/Medusa **MIT**, Lago **AGPL-3.0** (copyleft but commercial-OK), Invoice Ninja **Elastic-2.0**. NexusPay is the **only noncommercial option** in its category ([SPDX](https://spdx.org/licenses/), [polyformproject.org](https://polyformproject.org/licenses/noncommercial/1.0.0/)). |

---

## 4. Where it genuinely shines

Credited accurately, not inflated:

1. **Zero-trust multi-tenancy that most OSS payments projects simply don't ship.** Tenant-from-principal (never trust a client-supplied `X-Tenant-Id`), per-API-key scopes, HMAC-signed + replay-deduped webhooks, idempotency across capture/void/refund, maker-checker refunds, outbox, SSRF-guarded egress — and, importantly, **application-layer isolation is exercised by 8 cross-tenant/IDOR redteam tests**. Dormant Postgres RLS is designed as *defense-in-depth on top of that*, not the sole mechanism. Assembling this yourself on raw HyperSwitch is real work.

2. **Stripe-test-mode-class developer experience** — forced decline/3DS/processing outcomes, per-tenant test clocks, sandbox reset, idempotency-key inspection, typed SDK fixtures, and a `nexuspay` CLI — delivered *without weakening any security boundary*. This is an area where NexusPay is **arguably ahead of its OSS peers** and approaches the incumbent gold standard.

3. **Legible, well-bounded architecture.** Clean hexagonal Spring Modulith with build-time boundary verification, ports/adapters, outbox + CDC, and a circuit-breakered (hence swappable) HyperSwitch adapter. As a *reference* for how to build a hardened multi-tenant payments backend, it is unusually readable and disciplined.

4. **PCI-safe-by-design data handling** (never-store-raw-PAN, AES-256-GCM, tokenized/hosted flows) that can help an *adopter* shrink their own PCI scope — the correct, honest framing (software cannot itself be "PCI certified").

---

## 5. Where it falls short / why you'd pass

1. **License is a hard stop.** PolyForm-NC forbids commercial production use without a separate paid grant that does not exist today. This is strictly worse than *every* OSS peer and removes NexusPay from most shortlists **regardless of technical merit**.

2. **Bus factor of one.** A solo v0.x portfolio project with no company backing, no SLA, no support escalation. "Who maintains this if the author is unavailable?" has no acceptable enterprise answer — versus Juspay-backed HyperSwitch or a ~15-year Kill Bill.

3. **No production track record, no external validation.** Never processed real money; no external pentest, SOC 2, PCI attestation, or bug bounty. The security posture is a rigorous *internal* engineering standard and aspiration — **"battle-hardened / non-hackable" is not an externally-proven fact.**

4. **Tenant isolation is application-layer in practice.** Postgres RLS is implemented but **dormant** (`NEXUSPAY_RLS_ENFORCE:false`) with a human-gated cutover. The app layer *is* tested (see §4), but for a real multi-tenant deployment processing others' money, an enterprise will expect the database-level flip proven in prod.

5. **It doesn't move money and isn't a Stripe/Adyen replacement.** Early-stage or low-volume merchants should just buy a hosted processor — live in hours, PCI/fraud/settlement/liability outsourced. Adopting NexusPay adds operational, license, and bus-factor cost for capabilities a hosted PSP already provides.

6. **Key breadth items are stubs/deferred.** No portable card vault (the Spreedly moat), marketplace/split-payments and payout *execution* are stubs, reconciliation is partial, HSM is unimplemented (software AES-GCM only), and ML fraud/routing carry cold-start problems. By the owner's own roadmap it is well over a year from the parity it aspires to.

---

## 6. "Why not just use HyperSwitch / Kill Bill / Lago directly?"

**Honest answer: for a commercial team, you probably should — and NexusPay does not clearly beat that choice today.**

HyperSwitch already gives you the Apache-2.0, permissively-licensed, Juspay-backed, multi-connector orchestration engine — routing, retries, revenue recovery, vault, reconciliation, a PCI-compliant hosted SDK — for free, commercially usable, with real bus factor and production users. NexusPay's value-add is **not** more connectors or better routing (it inherits both from HyperSwitch). Its genuine delta is the **opinionated reference scaffold layered on top**:

1. strict **zero-trust multi-tenancy** (tenant-from-principal; per-API-key scopes; dormant RLS + cutover runbook),
2. a **CFO-facing ops/ledger layer** HyperSwitch is thinner on (double-entry ledger with FX gain/loss, maker-checker refunds, dispute-to-ledger postings, subscription dunning),
3. **HMAC-signed + replay-deduped webhooks** and idempotency across capture/void/refund with an outbox pattern, and
4. a **Stripe-test-mode-class developer harness** plus clean Spring Modulith boundaries.

That delta is real, well-engineered, and would take a competent team meaningful effort to assemble on raw HyperSwitch. **But it rarely outweighs three costs for a real company:** the PolyForm-NC license (HyperSwitch is Apache-2.0), bus-factor-of-one, and zero real-money track record. So the delta is worth it mainly as a **reference/learning architecture or a hardened starting scaffold** — not as a production dependency you'd choose over HyperSwitch itself. The same logic applies to Kill Bill (billing), Lago (metering), and Formance (ledger of record): each is permissively licensed, funded, and battle-tested in its lane.

---

## 7. Who should adopt it — and who shouldn't

**Choose NexusPay if:**

- You want a **legible, security-first reference architecture** for building a hardened multi-tenant payments backend on HyperSwitch — the zero-trust tenancy, HMAC+replay webhooks, idempotency, maker-checker, outbox, and dormant-RLS-with-runbook are genuinely more rigorous than most OSS payments projects ship.
- **Developer experience / test tooling is a top priority** and you value Stripe-test-mode-class affordances delivered without weakening any security boundary — where NexusPay is arguably ahead of its OSS peers.
- **You fit the license:** researchers, students, nonprofits, government/educational institutions, or teams evaluating the *patterns* — anyone who can legally use PolyForm-NC without a grant.
- You specifically want **this integrated ops/ledger delta** over raw HyperSwitch inside one cleanly-bounded Spring Modulith platform under a single zero-trust security model — **and** you are willing to negotiate a commercial license and accept single-maintainer risk.

**Pass on NexusPay if:**

- You're an **early-stage / low-volume merchant** — just buy Stripe/Square; live in hours, compliance and liability outsourced.
- You're **any commercial team that would otherwise reach for HyperSwitch, Kill Bill, Lago, or Formance** — those are permissively licensed, funded, community-backed, and battle-tested, making NexusPay's license + bus-factor + zero-track-record premium impossible to justify for production today.
- You need **external attestation** (SOC 2 / PCI Level 1 / pentest) or a **support SLA** now.

---

## 8. What it would take to become commercially adoptable

The path from impressive portfolio project to adoptable product is concrete:

1. **Relicense.** Move to **Apache-2.0 / MIT** for real uptake, or **AGPL open-core** if a commercial-grant business model is intended. As it stands, PolyForm-NC is the single biggest adoption blocker and undoes every technical strength for for-profit users.
2. **External security validation.** A real **third-party pentest** and, ideally, a **bug bounty** — so "hardened" stops being a self-assessment and becomes evidence.
3. **Activate RLS in production.** Flip `NEXUSPAY_RLS_ENFORCE` on behind the documented cutover and prove database-level isolation in a live deployment, turning app-layer-only isolation into true defense-in-depth.
4. **Earn a real-money track record.** At least one production deployment routing real traffic through HyperSwitch, with reconciliation proven against external truth at non-trivial volume.
5. **Reduce bus factor.** Additional maintainers, an org/backing, and a support story — the axis on which every funded OSS peer beats it today.
6. **Fill the deferred breadth** where it claims to compete: portable card vault, marketplace/split-payments + payout execution, and reconciliation depth (or an explicit "bring Formance/TigerBeetle for the ledger of record" posture).

---

### One-line, honest claim

> **"Unusually rigorous security + Stripe-test-mode-class developer experience for an OSS payments *reference* built on HyperSwitch"** — *not* "a production-ready Stripe or HyperSwitch replacement."

NexusPay makes the right architectural decisions and is a credible engineering artifact. It is a **reference architecture and portfolio-grade exemplar**, not a production product — and its own license currently forbids the commercial use its target adopters would need. That gap, not any technical shortfall, is what keeps it off real shortlists today.
