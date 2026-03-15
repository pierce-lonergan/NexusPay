#!/bin/bash
set -euo pipefail

# NexusPay Quickstart Script
# Starts all infrastructure, builds the app, and runs a smoke test.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
COMPOSE_FILE="$PROJECT_ROOT/docker/docker-compose.yml"
APP_URL="http://localhost:8090"
KEYCLOAK_URL="http://localhost:8180"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }

# ── Prerequisites ──────────────────────────────────────────────
check_prerequisites() {
    info "Checking prerequisites..."

    if ! command -v docker &>/dev/null; then
        error "Docker is not installed. Please install Docker Desktop."
        exit 1
    fi

    if ! docker compose version &>/dev/null; then
        error "Docker Compose V2 is not available."
        exit 1
    fi

    if ! command -v java &>/dev/null; then
        error "Java is not installed. Please install JDK 21."
        exit 1
    fi

    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 21 ]; then
        error "Java 21+ required, found Java $JAVA_VERSION"
        exit 1
    fi

    info "Prerequisites OK (Docker, Docker Compose, Java $JAVA_VERSION)"
}

# ── Infrastructure ─────────────────────────────────────────────
start_infrastructure() {
    info "Starting infrastructure (PostgreSQL, Kafka, Valkey, HyperSwitch, Keycloak)..."
    docker compose -f "$COMPOSE_FILE" up -d

    info "Waiting for services to become healthy..."
    local max_wait=180
    local waited=0

    while [ $waited -lt $max_wait ]; do
        local healthy
        healthy=$(docker compose -f "$COMPOSE_FILE" ps --format json 2>/dev/null | \
                  grep -c '"healthy"' || true)
        local total
        total=$(docker compose -f "$COMPOSE_FILE" ps --format json 2>/dev/null | \
                grep -c '"running"\|"healthy"' || true)

        if [ "$total" -ge 6 ]; then
            info "All $total services are running."
            break
        fi

        sleep 5
        waited=$((waited + 5))
        echo -n "."
    done

    if [ $waited -ge $max_wait ]; then
        warn "Some services may not be fully healthy. Check with: docker compose -f $COMPOSE_FILE ps"
    fi
}

# ── Build ──────────────────────────────────────────────────────
build_app() {
    info "Building NexusPay..."
    cd "$PROJECT_ROOT"
    ./gradlew build -x test --no-daemon --quiet
    info "Build complete."
}

# ── Smoke Test ─────────────────────────────────────────────────
smoke_test() {
    info "Running smoke test..."

    # Health check
    local health
    health=$(curl -sf "$APP_URL/actuator/health" 2>/dev/null || echo '{"status":"DOWN"}')
    if echo "$health" | grep -q '"UP"'; then
        info "Health check: UP"
    else
        warn "Health check: $health"
        warn "App may not be running. Start it with: ./gradlew bootRun"
        return
    fi

    info "Smoke test passed!"
}

# ── Main ───────────────────────────────────────────────────────
main() {
    echo ""
    echo "╔══════════════════════════════════════╗"
    echo "║       NexusPay Quickstart            ║"
    echo "║       v0.1.0                         ║"
    echo "╚══════════════════════════════════════╝"
    echo ""

    check_prerequisites
    start_infrastructure
    build_app

    echo ""
    info "Infrastructure is running!"
    info "  NexusPay API:    $APP_URL (start with: ./gradlew bootRun)"
    info "  Swagger UI:      $APP_URL/v1/swagger-ui"
    info "  Keycloak:        $KEYCLOAK_URL (admin/admin)"
    info "  HyperSwitch:     http://localhost:8080"
    info "  PostgreSQL:      localhost:5432 (nexuspay/nexuspay_local)"
    info "  Kafka:           localhost:9092"
    info "  Valkey:          localhost:6379"
    echo ""
    info "To stop: docker compose -f docker/docker-compose.yml down"
}

main "$@"
