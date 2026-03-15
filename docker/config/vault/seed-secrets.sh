#!/bin/bash
# ============================================================================
# Vault Secret Seeding Script — Local Development Only
#
# Seeds HashiCorp Vault with NexusPay secrets for local development.
# Run after docker-compose up when vault is healthy.
#
# Usage:
#   ./docker/config/vault/seed-secrets.sh
#
# Prerequisites:
#   - Vault running at http://localhost:8200
#   - Dev root token: nexuspay-dev-token
# ============================================================================

set -euo pipefail

export VAULT_ADDR="http://localhost:8200"
export VAULT_TOKEN="nexuspay-dev-token"

echo "=== Seeding Vault secrets for NexusPay local development ==="

# Enable KV v2 secrets engine (idempotent — ignores if already enabled)
vault secrets enable -path=secret -version=2 kv 2>/dev/null || true

# Database credentials
vault kv put secret/nexuspay/database \
  url="jdbc:postgresql://localhost:5432/nexuspay" \
  username="nexuspay" \
  password="nexuspay_local" \
  app-username="nexuspay_app" \
  app-password="nexuspay_app_local"

# HyperSwitch integration
vault kv put secret/nexuspay/hyperswitch \
  base-url="http://localhost:8080" \
  api-key="dev_api_key_for_local" \
  webhook-secret="webhook_secret_for_local"

# Keycloak
vault kv put secret/nexuspay/keycloak \
  url="http://localhost:8180" \
  realm="nexuspay" \
  admin-user="admin" \
  admin-password="admin"

# Kafka (plain credentials for local — SASL in production)
vault kv put secret/nexuspay/kafka \
  bootstrap-servers="localhost:29092"

# Valkey/Redis
vault kv put secret/nexuspay/valkey \
  url="redis://localhost:6379"

# Encryption keys (development only — use HSM in production)
vault kv put secret/nexuspay/encryption \
  master-key="dev-master-key-do-not-use-in-production" \
  hmac-key="dev-hmac-key-for-webhook-signatures"

echo "=== Vault secrets seeded successfully ==="
echo ""
echo "Verify with: vault kv list secret/nexuspay"
echo "Read with:   vault kv get secret/nexuspay/database"
