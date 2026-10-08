#!/usr/bin/env bash
set -euo pipefail

# Loads the test suite's sample mart into jobs_db, so /api/jobs has listings on a fresh volume
# without running the data pipeline. Run after `docker compose up` once db is healthy.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
. "$SCRIPT_DIR/common.sh"

require_cmd docker

FIXTURES="$REPO_ROOT/services/identity-service/app/src/test/resources/fixtures"
DB_USER="${POSTGRES_USER:-admin}"

cd "$REPO_ROOT"

# The fixture drops and recreates the mart, so never run it over listings the pipeline published.
jobs_sql() {
  docker compose exec -T db psql -U "$DB_USER" -d jobs_db -tAc "$1" | tr -d '[:space:]'
}
existing=0
if [[ "$(jobs_sql "SELECT to_regclass('analytics.fct_postings') IS NOT NULL")" == "t" ]]; then
  existing="$(jobs_sql "SELECT count(*) FROM analytics.fct_postings")"
fi
if [[ "$existing" != "0" && "${1:-}" != "--force" ]]; then
  fail "jobs_db already has $existing listings; pass --force to replace them with the sample"
fi

print_step "Loading sample job listings into jobs_db"
for f in analytics-schema analytics-seed; do
  docker compose exec -T db psql -U "$DB_USER" -d jobs_db -v ON_ERROR_STOP=1 \
    -c "SET ROLE analytics_user" -f - < "$FIXTURES/$f.sql"
done

echo "Done: http://localhost:3000"
