# Local Dev Sandbox (LITE stack) — INT-8

A minimal, HyperSwitch-less local environment for **test-mode** development.
You run only four infra containers (Postgres, Kafka, Valkey, Keycloak), boot the
app from Gradle, seed an `sk_test_` key + a webhook endpoint, and drive a payment
that is fully **in-process** — it never reaches a real payment processor.

> **Why "lite"?** Test mode (`sk_test_` keys) routes every payment to an
> in-process mock (`MockPaymentGatewayPort`), which does **zero network I/O** and
> never reaches HyperSwitch. So the full stack's HyperSwitch, Temporal, Vault,
> schema-registry, Debezium, and the Prometheus/Grafana observability tier are
> all unnecessary for day-to-day app development. The lite stack drops them.

---

## 1. Prerequisites

- **Docker Desktop** + **Docker Compose v2** (`docker compose version`)
- **JDK 21+** (`java -version`)
- **`jq`** and **`curl`** (used by the seed script) — `brew install jq` / `apt install jq`
- Windows: the seed script is bash; run it under **WSL** or **Git-Bash**, or use
  the PowerShell equivalent in §4.

---

## 2. Step 1 — start infra

```bash
docker compose -f docker/docker-compose.lite.yml up -d
```

Wait until everything is healthy:

```bash
docker compose -f docker/docker-compose.lite.yml ps
```

Ports (host): Postgres `5432`, Kafka `9092` (in-network) / `29092` (host
listener), Valkey `6379`, Keycloak `8180`. To remap any of these, see §8.

---

## 3. Step 2 — run the app (NOT a compose service)

The NexusPay app is **not** a container in either compose file. Boot it from
Gradle with the `local` profile, **Vault disabled**, and Kafka pointed at the
**host** listener:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :app:bootRun
```

The app listens on **`http://localhost:8090`**.

> **DX-4:** the `local` profile now **defaults** `spring.cloud.vault.enabled=false` and the Kafka
> bootstrap to `localhost:29092` (`application-local.yml`), so the two env vars that used to be required
> are now **optional** — set `SPRING_CLOUD_VAULT_ENABLED` / `KAFKA_BOOTSTRAP_SERVERS` only to **override**
> (e.g. remapped ports, §8). The rationale below is why each default matters.

**Why Kafka bootstrap = `localhost:29092`?** Kafka advertises two
listeners (`docker-compose.lite.yml:53`): `PLAINTEXT://kafka:9092` for in-network
clients and `PLAINTEXT_HOST://localhost:29092` for host clients. The base app default
is `localhost:9092` (`application.yml:59`), so the `local` profile now overrides it to
`localhost:29092` (DX-4). A host client that bootstrapped on `9092` would connect, then get handed the
advertised broker address `kafka:9092`, which does **not** resolve from the host —
so the outbox relay's `payment.succeeded` publish silently times out (10s broker
ack in `OutboxRelay`) and the webhook in step 5 never fires. Bootstrapping on
`29092` (the `PLAINTEXT_HOST` listener) advertises back `localhost:29092`, which
resolves. The app still **boots** without this (topic auto-create is non-fatal);
only the webhook loop breaks. For remapped ports override with `KAFKA_BOOTSTRAP_SERVERS=localhost:39092` (see §8).

**Why Vault disabled?** `spring-cloud-starter-vault-config`
is on the app classpath. In this project's configuration the Vault auto-config
attempts to authenticate at boot unless explicitly disabled — the integration
test profile disables it for exactly this reason (`application-test.yml`: *"Without
this, the Vault auto-config fails: Cannot create authentication mechanism for
TOKEN"*). The `local` profile now defaults `spring.cloud.vault.enabled=false` (DX-4); set
`SPRING_CLOUD_VAULT_ENABLED=true` to override. The in-process card-crypto (`nexuspay.vault.*`, software
provider) is independent and unaffected.

**Why everything else still boots without its container:**

| Excluded service | Why the app boots without it |
| --- | --- |
| **HyperSwitch** | The `RestClient` is built lazily; the first real `/payments` call would dial it, but `sk_test_` is routed to the in-process mock, so it is never dialed. Its health indicator degrades to DOWN gracefully and does not fail startup. Leave `HYPERSWITCH_BASE_URL` at its default (unreachable, never used). |
| **Temporal** | The worker config is `@ConditionalOnProperty(nexuspay.temporal.enabled=true)`, default false. No stub is created. |
| **schema-registry** | The Avro client is built lazily (no connect at construction); Avro dual-write defaults off. Nothing eagerly registers schemas at startup. |
| **Vault** | Disabled by the `local` profile default (DX-4); `SPRING_CLOUD_VAULT_ENABLED=true` re-enables. |

> The `local` profile is a recognized dev profile, so the startup secrets
> validator runs in **warn-only** mode (it does not fail-fast on the dev-default
> secrets).

**(DX-4) The `local` profile now bakes in `spring.cloud.vault.enabled=false` + Kafka `localhost:29092`**
(`application-local.yml`), both `${ENV:-default}`-wrapped so an env var still wins. The earlier INT-8
note (keep these as env-only to avoid changing the profile) was reversed because the silent failure mode
— a clean boot that never fires a webhook — cost integrators 30–60 min to first webhook (Snap DX critique
§2.3); a defaulted-but-overridable profile value is the safer default.

---

## 3b. (Option B) Run the app as a container — no local JDK/Gradle (DX-6)

If you would rather not install a JDK + Gradle, run the app from a container image
built by the repo `Dockerfile` (multi-stage: it compiles the bootJar, then ships a
slim non-root JRE on port 8090).

```bash
# Build locally (or pull the published image — see below)
docker build -t nexuspay-app .

# Run it joined to the LITE stack's network so it can reach Postgres/Kafka/Valkey/
# Keycloak by their in-network hostnames. Vault stays disabled; Kafka points at the
# in-network broker (kafka:9092) since the app is now INSIDE the compose network.
docker run --rm \
  --network "$(basename "$PWD")_default" \
  -e SPRING_PROFILES_ACTIVE=local \
  -e SPRING_CLOUD_VAULT_ENABLED=false \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -e NEXUSPAY_DB_URL=jdbc:postgresql://nexuspay-pg:5432/nexuspay \
  -e VALKEY_URL=redis://valkey:6379 \
  -e KEYCLOAK_URL=http://keycloak:8080 \
  -p 8090:8090 \
  nexuspay-app
```

> **Network name:** Compose names the default network `<project>_default` (the
> project defaults to the working-dir basename). Run `docker network ls` to confirm,
> or pass `-p`/`--network` to taste. From INSIDE the compose network use the
> in-network hostnames + ports (`kafka:9092`, `nexuspay-pg:5432`, `keycloak:8080`) —
> NOT the host-mapped `29092`/`8180` you use from `bootRun` on the host (§3).

**Published image.** A tag-or-dispatch-triggered workflow
(`.github/workflows/docker-image.yml`) builds and pushes
`ghcr.io/<owner>/nexuspay-app` to the GitHub Container Registry using the built-in
`GITHUB_TOKEN` (no extra secret). Push an `app-v*` tag (e.g. `app-v0.1.0`) or run it
via **workflow_dispatch** to publish `:latest`, `:<sha>`, and `:<semver>`. The same
workflow build-validates the `Dockerfile` on any PR that touches it.

> Test-mode safety is unchanged in a container: an `sk_test_` key still routes to the
> in-process mock (INT-3) — the image carries the same code, not a different profile.

---

## 4. Step 3 — seed a test key + webhook endpoint

```bash
bash scripts/dev/seed-local.sh
```

It (1) gets a Keycloak admin token via Direct Access Grant (seeded
`admin@nexuspay.test`), (2) `POST /v1/api-keys` `{role:operator, live:false}` →
an `sk_test_…` key, (3) `POST /v1/webhook-endpoints` (`https://example.com`) → a
`whsec_…` signing secret + endpoint id, then prints all three. **Both secrets are
shown once — copy them now.** The script contains no secret literals; all values
are dev defaults from `docker/.env` + the committed realm import, or minted at
runtime.

If your infra is on remapped ports, set the corresponding env vars first, e.g.
`KEYCLOAK_URL=http://localhost:18180 APP_URL=http://localhost:8090 bash scripts/dev/seed-local.sh`.

### Windows PowerShell equivalent (no WSL/Git-Bash)

```powershell
$kc  = "http://localhost:8180"
$app = "http://localhost:8090"

$token = (Invoke-RestMethod -Method Post `
  -Uri "$kc/realms/nexuspay/protocol/openid-connect/token" `
  -ContentType "application/x-www-form-urlencoded" `
  -Body @{ grant_type="password"; client_id="nexuspay-api";
           client_secret="nexuspay-api-secret";
           username="admin@nexuspay.test"; password="test123" }).access_token

$key = Invoke-RestMethod -Method Post -Uri "$app/v1/api-keys" `
  -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json" `
  -Body '{"name":"local-dev test key","role":"operator","live":false}'

$wh = Invoke-RestMethod -Method Post -Uri "$app/v1/webhook-endpoints" `
  -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json" `
  -Body '{"url":"https://example.com/webhooks","description":"local dev","events":["payment.succeeded","payment.refunded"]}'

Write-Host "test API key          : $($key.key)"
Write-Host "webhook signing secret: $($wh.secret)  (id: $($wh.id))"
```

---

## 5. Step 4 — drive a payment (routes to the in-process mock)

```bash
curl -X POST http://localhost:8090/v1/payments \
  -H "Authorization: Bearer sk_test_..." \
  -H "Content-Type: application/json" \
  -d '{"amount":1000,"currency":"USD"}'
```

Because the key is `sk_test_`, `GatedPaymentGateway` routes to
`MockPaymentGatewayPort` (payment id `pay_test_…`, connector `mock`). An
auto-capture create is terminal, so the mock webhook synthesizer emits the
canonical **`payment.succeeded`** event (`PAYMENT_CAPTURED → payment.succeeded`).

### Forcing test outcomes (TEST-MODE ONLY — moves no real money)

By default the in-process mock **always succeeds**. To exercise decline / failure
handling (and the failure webhooks) **without a real declined card**, set the
reserved control key **`__test_outcome`** in the payment's `metadata` on create
(case-insensitive value). This is honored **only** by the in-process mock, which
is reachable **only** for `sk_test_` (test-mode) keys — an `sk_live_` key can
never reach it, so a forced failure can never affect a real charge or touch
HyperSwitch. The reserved `__test_outcome` key is **stripped** server-side and
never appears in the delivered webhook's `data.metadata`.

```bash
curl -X POST http://localhost:8090/v1/payments \
  -H "Authorization: Bearer sk_test_..." \
  -H "Content-Type: application/json" \
  -d '{"amount":1000,"currency":"USD","metadata":{"__test_outcome":"declined"}}'
```

| `__test_outcome` | result status | `error_code` | webhook fired |
| --- | --- | --- | --- |
| *absent* / `succeed` / *unknown* | `succeeded` (or `requires_capture` if `capture_method=manual`) | — | `payment.succeeded` |
| `declined` | `failed` | `card_declined` | `payment.failed` |
| `insufficient_funds` | `failed` | `insufficient_funds` | `payment.failed` |
| `expired_card` | `failed` | `expired_card` | `payment.failed` |

> An **unknown** value is intentionally treated as success (logged at debug), so a
> typo can never silently break a happy-path test.

**Forcing a failed refund.** `RefundRequest` carries no metadata, so a forced
refund failure uses a documented **magic amount**: a refund whose minor-units
**`amount % 100 == 66`** (e.g. `1066`, `4266`) fails with status `failed` +
`error_code` `refund_failed` and fires **`payment.refund.failed`**. Any other
amount refunds successfully (`payment.refunded`).

```bash
# refund 1066 minor units against a pay_test_… payment -> forced refund failure
curl -X POST http://localhost:8090/v1/refunds \
  -H "Authorization: Bearer sk_test_..." \
  -H "Content-Type: application/json" \
  -d '{"payment_id":"pay_test_...","amount":1066,"currency":"USD"}'
```

### Saved payment-method test fixtures (TEST-MODE ONLY) — TEST-3b

A saved, multi-use payment method (`pm_…`) attaches a reusable credential to a
customer (`cus_…`) so an integrator can exercise a saved-card / off-session flow
**without any real card data**. **PCI: a raw PAN is never accepted or stored** —
the endpoint takes only display fields + an opaque `credential_ref`; a body that
smuggles a `number`/`cvc`/`cvv`/`pan`/`card` field is rejected **400** and never
persisted.

In **TEST mode** you supply a Stripe-style **fixture token** as `credential_ref`.
It resolves server-side to canned display fields plus a synthetic opaque
credential handle (which the later off-session charge resolves through the mock).
Fixture tokens are accepted **ONLY under an `sk_test_` key** — a fixture token
under a live key is rejected **400** (mode gate). A real opaque token under a test
key is allowed (it just isn't fixture-resolvable; it is stored verbatim).

| `credential_ref` fixture | brand | last4 | notes |
| --- | --- | --- | --- |
| `pm_card_visa` | visa | 4242 | exp 12/2034, funding credit |
| `pm_card_mastercard` | mastercard | 4444 | exp 12/2034, funding credit |
| `pm_card_amex` | amex | 0005 | exp 12/2034, funding credit |
| `pm_card_chargeDeclined` | visa | 0002 | saveable; the synthetic ref encodes a decline a future off-session charge honors — the **attach itself still succeeds** |

In **LIVE mode** you supply an already-tokenized opaque reference (e.g. a
`ptok_`/PSP pm id) as `credential_ref`; it is stored verbatim and the display
fields (`brand`/`last4`/…) come from the request body (the server never parses a
PAN).

```bash
# attach pm_card_visa to a customer under a TEST key
curl -X POST http://localhost:8090/v1/customers/cus_.../payment_methods \
  -H "Authorization: Bearer sk_test_..." \
  -H "Content-Type: application/json" \
  -d '{"type":"card","credential_ref":"pm_card_visa"}'
```

The response never exposes the tenant or the `credential_ref`:

```json
{
  "id": "pm_...",
  "object": "payment_method",
  "livemode": false,
  "type": "card",
  "customer": "cus_...",
  "card": { "brand": "visa", "last4": "4242", "exp_month": 12, "exp_year": 2034, "funding": "credit" },
  "metadata": null,
  "created": 1750000000
}
```

#### Off-session charge of a saved method (TEST-3c)

Once a method is attached you can charge it **off-session** (cardholder **not**
present) by passing its `pm_…` id as `payment_method` on **`POST /v1/payments`** —
the **same** endpoint as an inline create. No new endpoint, no real card data: the
server resolves the **tenant-owned** saved method (its opaque credential +
customer) and runs the **same** screening + idempotency + ledger + webhook path.

* The `pm_` is resolved **tenant-scoped** — a foreign/missing/detached `pm_` is a
  **404** (no existence oracle), so the charge never happens.
* The `pm_`'s `livemode` **must match the caller key mode** — a TEST `pm_` under
  an `sk_live_` key (or a LIVE `pm_` under `sk_test_`) is a **400**
  (`livemode_mismatch`). A test method can never be charged on a live key.
* `Idempotency-Key` is honored exactly as on any create (no double-charge on
  retry). `off_session` / `setup_future_usage` are optional charge hints.
  `mandate_id` is now a **validated consent gate** when cited (a null/absent
  `mandate_id` stays the pass-through — the 3c behavior is unchanged). See the
  TEST-3d mandate recipe below.

TEST recipe (deterministic, via the 3b fixtures):

| saved method | off-session charge result | webhook |
| --- | --- | --- |
| `pm_card_visa` (also mastercard/amex) | `succeeded` | `payment.captured` |
| `pm_card_chargeDeclined` | `failed` (`card_declined`) | `payment.failed` |

```bash
# charge a saved pm_ off-session under a TEST key
curl -X POST http://localhost:8090/v1/payments \
  -H "Authorization: Bearer sk_test_..." \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"amount":5000,"currency":"USD","payment_method":"pm_...","off_session":true}'
# pm_card_visa  -> {"status":"succeeded", ...}  + payment.captured
# pm_card_chargeDeclined -> {"status":"failed","error_code":"card_declined"} + payment.failed
```

#### Recording a mandate (consent) and gating an off-session charge (TEST-3d)

A **mandate** records a customer's stored consent to be charged off-session. It is
the consent record an off-session charge's `mandate_id` references — once cited it
is a **real consent gate**, not a dangling string. Mandates live under
**`/v1/mandates`** and REUSE the `customers:read` / `customers:write` scopes (a
mandate is part of the saved-credential consent cluster).

A mandate is created **from a saved method** (`pm_…`): the `pm_` is resolved
**tenant-scoped** (a foreign/missing one is a **404**, no oracle), its `livemode`
must match the caller key mode (else **400** `livemode_mismatch`), and the
mandate's `customer` is **derived from the `pm_`'s owner** (never client-supplied).
A created mandate is **`ACTIVE`**. Endpoints: `POST /v1/mandates`,
`GET /v1/mandates/{id}`, `GET /v1/mandates`, `POST /v1/mandates/{id}/revoke`
(deactivate → `INACTIVE` + `revoked_at`; a revoked mandate **stays retrievable**).

TEST recipe (create a customer → attach `pm_card_visa` → record a mandate →
off-session charge citing it → succeeds):

```bash
# 1) record the mandate from a saved pm_ (status ACTIVE)
curl -X POST http://localhost:8090/v1/mandates \
  -H "Authorization: Bearer sk_test_..." \
  -H "Content-Type: application/json" \
  -d '{"payment_method":"pm_...","type":"MULTI_USE","scenario":"recurring"}'
# -> {"id":"mandate_...","object":"mandate","status":"ACTIVE","customer":"cus_...","payment_method":"pm_..."}

# 2) off-session charge CITING the mandate -> succeeds (the consent gate passes)
curl -X POST http://localhost:8090/v1/payments \
  -H "Authorization: Bearer sk_test_..." \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"amount":5000,"currency":"USD","payment_method":"pm_...","off_session":true,"mandate_id":"mandate_..."}'
```

When a `mandate_id` is cited the charge validates it, in order, against the
**tenant-resolved** `pm_`:

| cited mandate | charge result |
| --- | --- |
| ACTIVE + authorizes the charged `pm_` | proceeds (charges) |
| foreign / missing | **404** (no oracle) — never charges |
| revoked / INACTIVE | **400** `invalid_mandate` — never charges |
| authorizes a different `pm_` | **400** `mandate_payment_method_mismatch` — never charges |
| **no `mandate_id`** (null/absent) | unchanged pass-through (3c behavior) |

> **`type` is a descriptive hint, not an enforced control (3d).** A `SINGLE_USE`
> mandate is **not** self-consumed: it stays `ACTIVE` after a charge and the gate
> checks only tenant + `ACTIVE` + matching `pm_` (it does **not** consider `type`),
> so a `SINGLE_USE` mandate can be cited on **more than one** off-session charge.
> Do not rely on single-use enforcement — **revoke** the mandate to stop further
> use. (Single-use consumption is a deferred later increment.)

---

## 5b. Trigger a synthetic webhook + inspect a delivery (TEST-4a)

Two developer-testability helpers that reuse the normal signed delivery pipeline —
no real payment required.

### Trigger a synthetic webhook (`POST /v1/test/events`)

**TEST-MODE ONLY** (a LIVE key → `404`, no oracle). Scope: `webhooks:write`.
Synthesizes a canonical webhook of the chosen `type` and delivers it to **your
own** tenant's enabled endpoints through the same signed pipeline a real event
would, stamped `livemode: false`.

```bash
# trigger a payment.succeeded webhook to your own endpoints
curl -X POST http://localhost:8090/v1/test/events \
  -H "Authorization: Bearer sk_test_..." \
  -H "Content-Type: application/json" \
  -d '{"type":"payment.succeeded","data":{"amount":4242,"currency":"USD"}}'
# -> 202 {"id":"evt_...","type":"payment.succeeded","livemode":false,"object":{...}}
```

- `type` MUST be a canonical dotted event name (see `WebhookEventType`); an unknown
  name or `"*"` → `400`.
- `id` is OPTIONAL (an opaque test aggregate id; defaulted with the
  aggregate-correct prefix `pay_test_*` / `dp_test_*` — NOT resolved against any
  aggregate).
- `data` is OPTIONAL (overlaid onto the synthesized `data.object`).
- The tenant is always **yours** (from the key) — never a body/header — so the
  event fans out only to your own endpoints. **A test key cannot synthesize a live
  event or target another tenant.**

> **Delivery gap (by design):** the event only delivers if your tenant has an
> **ENABLED endpoint subscribed** to that `type` (or `"*"`). With no matching
> endpoint the event silently no-ops — register/subscribe an endpoint first (and
> see §6 for why it must be a public HTTPS URL, e.g. via ngrok).

### Inspect a delivery's body + signature (`GET .../body`, `.../signature`)

Owner-scoped read endpoints to debug **signature verification**. Scope:
`webhooks:read`. Both resolve tenant-scoped — a foreign/absent delivery id → `404`
(no oracle). Find a delivery id from `GET /v1/webhook-deliveries`.

```bash
# the EXACT delivered bytes that were signed (your own delivery only)
curl http://localhost:8090/v1/webhook-deliveries/whd_.../body \
  -H "Authorization: Bearer sk_test_..."
# -> {"id":"whd_...","endpoint_id":"we_...","event_id":"evt_...",
#     "event_type":"payment.succeeded","canonical_body":"{...exact bytes...}"}

# recompute the HMAC-SHA256 signature over those bytes (never returns the secret)
curl http://localhost:8090/v1/webhook-deliveries/whd_.../signature \
  -H "Authorization: Bearer sk_test_..."
# -> {"id":"whd_...","endpoint_id":"we_...","algorithm":"HmacSHA256",
#     "signature":"<hex>","rotated_secret_caveat":"..."}
```

- **The signing secret is NEVER returned** — not by `/body`, not by `/signature`,
  not in any log or error. The `/signature` route reads the endpoint's secret only
  transiently to recompute the HMAC, then discards it.
- **ROTATED-SECRET CAVEAT:** `/signature` recomputes with the endpoint's **CURRENT**
  secret (exactly as the sender signs per attempt). If you **rotated** the secret
  AFTER the original delivery, the recomputed signature **differs** from the
  originally-delivered `X-NexusPay-Signature` header — it is *not* proof the
  original delivery was mis-signed. The `rotated_secret_caveat` field says so
  in-band.
- To verify: recompute `HMAC-SHA256(canonical_body, whsec_…)` yourself (e.g. with
  the `@nexus-pay/node` SDK's `verifyWebhook`) and compare against the `/signature`
  result — they match when the secret has not been rotated since delivery.

---

## 6. SEC-4b caveat — webhooks CANNOT be delivered to `localhost`

`WebhookUrlValidator` is **fail-closed**: at both registration and delivery it
**requires `https`** and a **publicly routable resolved IP**. It rejects
`localhost`, loopback, private (RFC1918), link-local, ULA, CGNAT, and
cloud-metadata addresses. That is why the seed registers `https://example.com`,
not `localhost`.

To actually **receive** a webhook locally, choose one of:

1. **Public HTTPS tunnel** — run `ngrok http 4000` (or similar), then register the
   public `https://<id>.ngrok.io/...` URL as your endpoint. Deliveries will reach
   your local listener through the tunnel.
2. **Verify signatures offline** — take the once-shown `whsec_…` secret and
   verify the HMAC signature against test events you construct yourself (e.g. with
   the `@nexus-pay/node` SDK), without any outbound delivery.

---

## 7. Test mode never moves real money (INT-3)

`sk_test_` payments are fully in-process. `MockPaymentGatewayPort` is a hard
invariant: it imports no HyperSwitch/RestClient/HTTP client and does **zero
network I/O** — state lives only in an in-memory map. A test key never reaches
the HyperSwitch adapter, by construction (enforced by an arch test).

---

## 8. Port collisions / overrides

**Use the env-var route** — `docker-compose.lite.yml` templates every host port
(`${NEXUSPAY_PG_PORT}`, `${KAFKA_PORT}`, `${KAFKA_HOST_PORT}`, `${VALKEY_PORT}`,
`${KEYCLOAK_PORT}`, each defaulting to the full-compose value). Set them in your
shell or a `docker/.env`-style file and the host binding is **replaced cleanly**:

```bash
NEXUSPAY_PG_PORT=15432 KAFKA_PORT=19092 KAFKA_HOST_PORT=39092 \
VALKEY_PORT=16379 KEYCLOAK_PORT=18180 \
docker compose -f docker/docker-compose.lite.yml up -d
```

> **Avoid a compose-file `-f` override for port remapping — use the env vars above.** Compose merges the
> `ports` list **additively** across `-f` files — short-form entries are concatenated, not replaced — so
> a second `ports` entry like `19092:9092` on top of the base `9092:9092` leaves **both** bound, and the
> original colliding port stays bound (the remap fails to dodge the collision). The `${..._PORT:-default}`
> env-var route re-templates the single base entry, so it remaps cleanly. (DX-4 removed the former
> `docker-compose.override.yml.example`, which only demonstrated this footgun.)

Either way, host port remapping is the only thing this changes. If you remap,
update the matching `bootRun` env vars so the app reaches the moved services
(note `KAFKA_BOOTSTRAP_SERVERS` points at the remapped **`PLAINTEXT_HOST`**
listener `39092`, the host-port analogue of the default `29092` from §3):

```bash
SPRING_PROFILES_ACTIVE=local SPRING_CLOUD_VAULT_ENABLED=false \
KEYCLOAK_URL=http://localhost:18180 \
NEXUSPAY_DB_URL=jdbc:postgresql://localhost:15432/nexuspay \
KAFKA_BOOTSTRAP_SERVERS=localhost:39092 \
VALKEY_URL=redis://localhost:16379 \
./gradlew :app:bootRun
```

---

## 9. Teardown

```bash
docker compose -f docker/docker-compose.lite.yml down       # stop + remove containers
docker compose -f docker/docker-compose.lite.yml down -v    # also wipe nexuspay-pg-data
```
