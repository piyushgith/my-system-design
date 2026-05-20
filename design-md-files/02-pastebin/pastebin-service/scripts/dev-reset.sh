#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/env.sh"
cd "$PROJECT_ROOT"

echo "WARNING: This removes all local database and object storage volumes."
read -r -p "Continue? [y/N] " confirm
if [[ ! "$confirm" =~ ^[Yy]$ ]]; then
  echo "Aborted."
  exit 1
fi

docker compose down -v
"$(dirname "$0")/dev-up.sh"
"$(dirname "$0")/flyway-migrate.sh"
echo "Local environment reset complete."
