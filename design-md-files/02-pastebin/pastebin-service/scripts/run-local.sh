#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/env.sh"
cd "$PROJECT_ROOT"

"$(dirname "$0")/dev-up.sh"
"$(dirname "$0")/flyway-migrate.sh"

MVN="${PROJECT_ROOT}/mvnw"
if [[ ! -x "$MVN" ]]; then
  MVN="mvn"
fi

echo "Starting pastebin-service (profile: ${SPRING_PROFILES_ACTIVE})..."
exec "$MVN" spring-boot:run \
  -Dspring-boot.run.profiles="$SPRING_PROFILES_ACTIVE" \
  -Dspring-boot.run.jvmArguments="-DJWT_SECRET=${JWT_SECRET}"
