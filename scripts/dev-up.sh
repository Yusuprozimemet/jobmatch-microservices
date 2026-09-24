#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
. "$SCRIPT_DIR/common.sh"

require_cmd docker

print_step "Starting local db, backend, api-gateway, and frontend"
(
  cd "$REPO_ROOT"
  docker compose up -d db backend api-gateway frontend
)

echo
echo "App stack is up."
echo "Frontend: http://localhost:3000"
echo "Backend docs, through the gateway: http://localhost:8080/api/docs"
