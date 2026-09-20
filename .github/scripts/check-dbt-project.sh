#!/usr/bin/env bash
# Validate the dbt project in CI without a warehouse connection.
#
# Run from the repository root. Requires uv and the data/ project.
#
# 1. Conflict-marker scan (catches unresolved git merges — sqlfmt misses these)
# 2. dbt parse (project refs, Jinja, YAML — dummy DATABRICKS_TOKEN, no warehouse)
# 3. SQLFluff lint on changed SQL (syntax/PRS only — sqlfmt still owns layout)
#
# Env (set by data-ci-cd.yaml):
#   DATABRICKS_HOST, DATABRICKS_HTTP_PATH, DATABRICKS_CATALOG
#   DBT_SCHEMA — default ci
#   LANDING_PATH — optional; defaults from catalog
#   FILES — optional; space/newline-separated SQL paths relative to data/
#             (same as the sqlfmt step). When empty, SQLFluff is skipped.
#
# Expects the workflow install step to run:
#   uv sync --extra dev --extra sync --extra dbt
# (this script does not re-sync — a partial sync would drop psycopg before pytest).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DATA="${ROOT}/data"
DBT="${DATA}/dbt"

if [ ! -d "${DBT}" ]; then
  echo "No data/dbt project; skipping dbt checks."
  exit 0
fi

conflict_hits="$(
  rg -n '<<<<<<<|>>>>>>>|^=======$|<<\s+<<\s+<<|>>\s+>>' \
    "${DATA}/dbt" "${DATA}/src" "${DATA}/tests" 2>/dev/null || true
)"
if [ -n "${conflict_hits}" ]; then
  echo "${conflict_hits}"
  echo "::error::Unresolved git merge conflict markers under data/. Search for <<<<<<< and fix before merging."
  exit 1
fi

export DATABRICKS_HOST="${DATABRICKS_HOST:?DATABRICKS_HOST must be set}"
export DATABRICKS_HTTP_PATH="${DATABRICKS_HTTP_PATH:?DATABRICKS_HTTP_PATH must be set}"
export DATABRICKS_CATALOG="${DATABRICKS_CATALOG:?DATABRICKS_CATALOG must be set}"
export DBT_SCHEMA="${DBT_SCHEMA:-ci}"
export LANDING_PATH="${LANDING_PATH:-/Volumes/${DATABRICKS_CATALOG}/landing/prod}"
# profiles.yml requires a token for the dev target; parse does not connect.
export DATABRICKS_TOKEN="${DATABRICKS_TOKEN:-ci}"

cd "${DATA}"

echo "Running dbt parse..."
uv run dbt parse --project-dir dbt --profiles-dir dbt

if [ -z "${FILES:-}" ]; then
  echo "No SQL files changed under data/; skipping SQLFluff."
else
  dbt_sql=()
  while IFS= read -r path; do
    [ -z "${path}" ] && continue
    case "${path}" in
      dbt/*.sql | dbt/**/*.sql) dbt_sql+=("${path}") ;;
    esac
  done <<EOF
${FILES}
EOF
  if [ "${#dbt_sql[@]}" -eq 0 ]; then
    echo "No changed dbt SQL files; skipping SQLFluff."
  else
    echo "Running SQLFluff (syntax only) on: ${dbt_sql[*]}"
    uv run sqlfluff lint "${dbt_sql[@]}" --config dbt/.sqlfluff
  fi
fi

echo "dbt checks succeeded."
