#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/env.sh"
cd "$PROJECT_ROOT"

echo "Starting local infrastructure (PostgreSQL, Redis, MinIO)..."
docker compose up -d

echo "Waiting for PostgreSQL..."
until docker compose exec -T postgres pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; do
  sleep 1
done

echo "Waiting for Redis..."
until docker compose exec -T redis redis-cli ping | grep -q PONG; do
  sleep 1
done

echo "Waiting for MinIO..."
until curl -sf "$MINIO_ENDPOINT/minio/health/live" >/dev/null; do
  sleep 1
done

echo "Local infrastructure is ready."
echo "  PostgreSQL: ${DB_HOST}:${DB_PORT}/${DB_NAME}"
echo "  Redis:      ${REDIS_HOST}:${REDIS_PORT}"
echo "  MinIO:      ${MINIO_ENDPOINT} (console: http://localhost:9001)"
