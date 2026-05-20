#!/usr/bin/env bash
# Shared environment for pastebin-service scripts.
# Override any value by exporting it before running a script.

export PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-pastebin_dev}"
export DB_USER="${DB_USER:-pastebin}"
export DB_PASSWORD="${DB_PASSWORD:-pastebin}"

export FLYWAY_URL="${FLYWAY_URL:-jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}}"
export FLYWAY_USER="${FLYWAY_USER:-${DB_USER}}"
export FLYWAY_PASSWORD="${FLYWAY_PASSWORD:-${DB_PASSWORD}}"
export FLYWAY_SCHEMAS="${FLYWAY_SCHEMAS:-paste,identity}"
export FLYWAY_DEFAULT_SCHEMA="${FLYWAY_DEFAULT_SCHEMA:-paste}"

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
export JWT_SECRET="${JWT_SECRET:-pastebin-dev-secret-change-in-production-must-be-256-bits-long!!}"

export REDIS_HOST="${REDIS_HOST:-localhost}"
export REDIS_PORT="${REDIS_PORT:-6379}"
export MINIO_ENDPOINT="${MINIO_ENDPOINT:-http://localhost:9000}"
