#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/env.sh"
cd "$PROJECT_ROOT"

MVN="${PROJECT_ROOT}/mvnw"
if [[ ! -x "$MVN" ]]; then
  MVN="mvn"
fi

"$MVN" clean test "$@"
