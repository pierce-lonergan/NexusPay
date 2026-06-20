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
