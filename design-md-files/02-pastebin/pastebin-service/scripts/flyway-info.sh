#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/env.sh"
cd "$PROJECT_ROOT"

MVN="${PROJECT_ROOT}/mvnw"
if [[ ! -x "$MVN" ]]; then
  MVN="mvn"
fi

"$MVN" -Plocal flyway:info \
  -Dflyway.url="$FLYWAY_URL" \
  -Dflyway.user="$FLYWAY_USER" \
  -Dflyway.password="$FLYWAY_PASSWORD" \
  -Dflyway.schemas="$FLYWAY_SCHEMAS" \
  -Dflyway.defaultSchema="$FLYWAY_DEFAULT_SCHEMA"
