#!/usr/bin/env bash
# GitHub Actions wrapper: write changed data/ paths as job outputs.
#
# Writes GitHub Actions outputs (multiline):
#   python  — paths relative to data/, *.py
#   sql     — paths relative to data/, *.sql
#   typed   — paths under data/src/ or data/tests/
#   range   — the git diff range used (may be empty)
#
# Required env (set by the workflow):
#   EVENT_NAME   — github.event_name
# Optional, depending on the event:
#   PR_BASE, PR_HEAD     — pull_request base/head SHAs
#   PUSH_BEFORE, PUSH_SHA — push before/after SHAs
#   GITHUB_OUTPUT          — path to the Actions output file
#
# Must run from the repo root with fetch-depth: 0 on checkout.
# Core logic lives in changed-data-files.sh.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=changed-data-files.sh
source "${SCRIPT_DIR}/changed-data-files.sh"

changed_data_files_resolve_range
changed_data_files_collect "$CHANGED_DATA_FILES_RANGE"

{
  echo "python<<EOF"
  printf '%s\n' "${CHANGED_DATA_FILES_PY[@]+"${CHANGED_DATA_FILES_PY[@]}"}"
  echo "EOF"
  echo "sql<<EOF"
  printf '%s\n' "${CHANGED_DATA_FILES_SQL[@]+"${CHANGED_DATA_FILES_SQL[@]}"}"
  echo "EOF"
  echo "typed<<EOF"
  printf '%s\n' "${CHANGED_DATA_FILES_TYPED[@]+"${CHANGED_DATA_FILES_TYPED[@]}"}"
  echo "EOF"
  echo "range=${CHANGED_DATA_FILES_RANGE}"
} >> "${GITHUB_OUTPUT:?GITHUB_OUTPUT must be set}"
