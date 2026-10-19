#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."
compose=(docker compose --env-file .env.local)
command_name="${1:-up}"
if [[ ! -f .env.local ]]; then
  cp .env.local.example .env.local
  chmod 600 .env.local
  echo '.env.local created with local-only example credentials.'
fi
case "$command_name" in
  up)
    docker info >/dev/null
    for key in private-key.pem public-key.pem; do
      # Default keys are checked here; Compose validates custom bind paths.
      if ! grep -Eq '^LOCAL_KEY_DIRECTORY=\./local-keys[[:space:]]*$' .env.local; then break; fi
      [[ -f "local-keys/$key" ]] || { echo "Missing local-keys/$key" >&2; exit 1; }
    done
    bash ./gradlew bootJar
    "${compose[@]}" up -d --build --wait --wait-timeout 420
    "${compose[@]}" ps
    ;;
  down) "${compose[@]}" down ;;
  ps) "${compose[@]}" ps ;;
  logs) shift; "${compose[@]}" logs -f --tail=100 "$@" ;;
  *) echo "Usage: bash infrastructure/local.sh {up|down|ps|logs [service]}" >&2; exit 2 ;;
esac
