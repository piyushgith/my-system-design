#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/env.sh"
cd "$PROJECT_ROOT"

echo "Stopping local infrastructure..."
docker compose down
echo "Done."
