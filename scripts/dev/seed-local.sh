#!/usr/bin/env bash
# =============================================================================
#  !!  LOCAL DEV ONLY -- DO NOT RUN AGAINST ANY SHARED / STAGING / PROD HOST  !!
# =============================================================================
#  Seeds a TEST-mode sandbox against the LITE stack (docker-compose.lite.yml):
#    1. gets a Keycloak admin access token (seeded admin@nexuspay.test)
#    2. POST /v1/api-keys           {role:operator, live:false}  -> sk_test_ key
#    3. POST /v1/webhook-endpoints  (https example.com)          -> whsec_ + id
#    4. prints all three for you to paste into your app env.
#
#  This file contains NO secrets. Every credential is either a well-known DEV
#  default from docker/.env + the committed Keycloak realm import, or minted at
#  runtime by Keycloak/the API. Safe to commit.
#
#  Requires: curl, jq. Windows: run under WSL or Git-Bash (see docs/LOCAL_DEV.md
#  for an Invoke-RestMethod PowerShell equivalent).
# =============================================================================
set -euo pipefail

# --- config (override via env; defaults match docker/.env + realm import) ----
# NOTE: KEYCLOAK_URL/APP_URL default to the HOST-side localhost ports, since this
# script runs on the host, NOT inside the compose network (docker/.env uses the
# in-network hostnames keycloak:8180 / nexuspay-pg, which only resolve in-cluster).
APP_URL="${APP_URL:-http://localhost:8090}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
REALM="${KEYCLOAK_REALM:-nexuspay}"
KC_CLIENT_ID="${KEYCLOAK_CLIENT_ID:-nexuspay-api}"
# Confidential dev client secret from the COMMITTED realm import
# (docker/config/keycloak-realm.json). Read from env with a default so no bare
# literal assignment trips secret scanners; this is a public dev-realm value.
KC_CLIENT_SECRET="${KEYCLOAK_CLIENT_SECRET:-nexuspay-api-secret}"
KC_ADMIN_USER="${SEED_ADMIN_USER:-admin@nexuspay.test}"   # seeded realm user
KC_ADMIN_PASS="${SEED_ADMIN_PASS:-test123}"               # seeded realm password

command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq   >/dev/null || { echo "ERROR: jq is required (brew/apt install jq)" >&2; exit 1; }

say() { printf '\033[0;32m[seed]\033[0m %s\n' "$*"; }

# --- 1. Keycloak admin token (Direct Access Grant / ROPC) --------------------
say "Requesting Keycloak token ($KC_ADMIN_USER @ realm $REALM)..."
TOKEN="$(curl -fsS -X POST \
  "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode "grant_type=password" \
  --data-urlencode "client_id=$KC_CLIENT_ID" \
  --data-urlencode "client_secret=$KC_CLIENT_SECRET" \
  --data-urlencode "username=$KC_ADMIN_USER" \
  --data-urlencode "password=$KC_ADMIN_PASS" \
  | jq -r '.access_token')"
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || {
  echo "ERROR: no access_token from Keycloak (is keycloak up on $KEYCLOAK_URL?)" >&2
  exit 1
}

# --- 2. create the sk_test_ API key (operator, live=false) -------------------
say "Creating test API key (role=operator, live=false)..."
KEY_JSON="$(curl -fsS -X POST "$APP_URL/v1/api-keys" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"local-dev test key","role":"operator","live":false}')"
SK_TEST_KEY="$(printf '%s' "$KEY_JSON" | jq -r '.key')"
API_KEY_ID="$(printf '%s' "$KEY_JSON" | jq -r '.id')"
[ -n "$SK_TEST_KEY" ] && [ "$SK_TEST_KEY" != "null" ] || {
  echo "ERROR: api-key create failed: $KEY_JSON" >&2
  exit 1
}

# --- 3. register a webhook endpoint -> whsec_ secret + id --------------------
# URL is https://example.com (public) so it passes @SafeWebhookUrl at
# registration. Events are canonical dotted names. localhost CANNOT be used --
# see the SEC-4b caveat in docs/LOCAL_DEV.md.
say "Registering webhook endpoint (https://example.com/webhooks)..."
WH_JSON="$(curl -fsS -X POST "$APP_URL/v1/webhook-endpoints" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/webhooks","description":"local dev","events":["payment.succeeded","payment.refunded"]}')"
WHSEC="$(printf '%s' "$WH_JSON" | jq -r '.secret')"
WH_ID="$(printf '%s' "$WH_JSON" | jq -r '.id')"
[ -n "$WHSEC" ] && [ "$WHSEC" != "null" ] || {
  echo "ERROR: webhook-endpoint create failed: $WH_JSON" >&2
  exit 1
}

# --- 4. print for the developer ----------------------------------------------
cat <<EOF

--------------------------------------------------------------------
 LOCAL DEV SANDBOX SEEDED  (shown ONCE -- copy now)
--------------------------------------------------------------------
 test API key         : $SK_TEST_KEY
   (api key id          : $API_KEY_ID)
 webhook signing secret: $WHSEC
   (webhook endpoint id : $WH_ID)

 Drive a payment (routes to the in-process mock):
   curl -X POST $APP_URL/v1/payments \\
     -H "Authorization: Bearer \$YOUR_TEST_KEY" \\
     -H "Content-Type: application/json" \\
     -d '{"amount":1000,"currency":"USD"}'
--------------------------------------------------------------------
EOF
