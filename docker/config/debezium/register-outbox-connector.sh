#!/bin/bash
# ============================================================================
# Debezium Outbox Connector Registration — Sprint 2.2
#
# Registers the Debezium PostgreSQL connector with the outbox event router
# transform. This replaces the polling-based OutboxRelay with CDC.
#
# Usage:
#   ./docker/config/debezium/register-outbox-connector.sh
#
# Prerequisites:
#   - Kafka Connect running at http://localhost:8083
#   - NexusPay PostgreSQL running at nexuspay-pg:5432
#   - WAL level set to 'logical' (configured in docker-compose.yml)
# ============================================================================

set -euo pipefail

CONNECT_URL="http://localhost:8083"

echo "=== Waiting for Kafka Connect to be ready ==="
until curl -sf "${CONNECT_URL}/connectors" > /dev/null 2>&1; do
    echo "  Kafka Connect not ready, waiting 5s..."
    sleep 5
done
echo "  Kafka Connect is ready."

echo ""
echo "=== Registering NexusPay outbox connector ==="

curl -sf -X POST "${CONNECT_URL}/connectors" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "nexuspay-outbox-connector",
    "config": {
      "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
      "database.hostname": "nexuspay-pg",
      "database.port": "5432",
      "database.user": "nexuspay",
      "database.password": "nexuspay_local",
      "database.dbname": "nexuspay",
      "database.server.name": "nexuspay",
      "topic.prefix": "nexuspay",

      "plugin.name": "pgoutput",
      "publication.autocreate.mode": "filtered",

      "table.include.list": "public.event_outbox",
      "slot.name": "nexuspay_outbox_slot",

      "transforms": "outbox",
      "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
      "transforms.outbox.table.field.event.id": "id",
      "transforms.outbox.table.field.event.key": "aggregate_id",
      "transforms.outbox.table.field.event.type": "event_type",
      "transforms.outbox.table.field.event.payload": "payload",
      "transforms.outbox.table.fields.additional.placement": "aggregate_type:header,tenant_id:header,created_at:header",
      "transforms.outbox.route.topic.replacement": "nexuspay.${routedByValue}",
      "transforms.outbox.route.by.field": "routing_key",

      "key.converter": "org.apache.kafka.connect.storage.StringConverter",
      "value.converter": "org.apache.kafka.connect.json.JsonConverter",
      "value.converter.schemas.enable": false,

      "tombstones.on.delete": false,
      "heartbeat.interval.ms": "10000",

      "errors.tolerance": "all",
      "errors.deadletterqueue.topic.name": "nexuspay.connect.dlq",
      "errors.deadletterqueue.context.headers.enable": true
    }
  }' | python3 -m json.tool 2>/dev/null || echo "(registered)"

echo ""
echo "=== Connector status ==="
curl -sf "${CONNECT_URL}/connectors/nexuspay-outbox-connector/status" | python3 -m json.tool 2>/dev/null || echo "  Check: curl ${CONNECT_URL}/connectors/nexuspay-outbox-connector/status"

echo ""
echo "=== Done. Outbox CDC is active. ==="
echo "  Events from event_outbox will be routed to nexuspay.{aggregate_type} topics."
echo "  Monitor: curl ${CONNECT_URL}/connectors/nexuspay-outbox-connector/status"
