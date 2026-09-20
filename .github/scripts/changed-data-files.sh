#!/usr/bin/env bash
# Resolve a git diff range and list changed files under data/.
#
# Sourceable (sets shell variables) or runnable from the repo root:
#   source .github/scripts/changed-data-files.sh
#   changed_data_files_resolve_range   # sets CHANGED_DATA_FILES_RANGE
#   changed_data_files_collect         # sets CHANGED_DATA_FILES_{PY,SQL,TYPED} arrays
#
# CLI:
#   bash .github/scripts/changed-data-files.sh [range]
#   EVENT_NAME=push PUSH_BEFORE=... PUSH_SHA=... bash .github/scripts/changed-data-files.sh
#
# When range is omitted, resolve from CI env (same as the workflow):
#   EVENT_NAME, PR_BASE, PR_HEAD, PUSH_BEFORE, PUSH_SHA
#
# Must run from the repo root with enough git history (fetch-depth: 0 in CI).

changed_data_files_resolve_range() {
  local event="${EVENT_NAME:-}"
  local range=""
  local before

  case "$event" in
    pull_request)
      range="${PR_BASE}...${PR_HEAD}"
      ;;
    push)
      before="${PUSH_BEFORE:-}"
      # First push to a new ref has a zero before SHA — fall back to the parent.
      if [ "$before" = "0000000000000000000000000000000000000000" ] || [ -z "$before" ]; then
        if git rev-parse --verify HEAD^ >/dev/null 2>&1; then
          range="HEAD^...HEAD"
        fi
      else
        range="${before}...${PUSH_SHA}"
      fi
      ;;
    *)
      # workflow_dispatch (and anything else): tip vs parent.
      if git rev-parse --verify HEAD^ >/dev/null 2>&1; then
        range="HEAD^...HEAD"
      fi
      ;;
  esac

  CHANGED_DATA_FILES_RANGE="$range"
}

changed_data_files_collect() {
  local range="${1-${CHANGED_DATA_FILES_RANGE-}}"
  local files=()
  local f rel

  CHANGED_DATA_FILES_PY=()
  CHANGED_DATA_FILES_SQL=()
  CHANGED_DATA_FILES_TYPED=()

  if [ -n "$range" ]; then
    while IFS= read -r line; do
      [ -n "$line" ] && files+=("$line")
    done < <(git diff --name-only --diff-filter=ACMR "$range" -- 'data/')
  fi

  for f in "${files[@]+"${files[@]}"}"; do
    rel="${f#data/}"
    case "$rel" in
      *.py) CHANGED_DATA_FILES_PY+=("$rel") ;;
      *.sql) CHANGED_DATA_FILES_SQL+=("$rel") ;;
    esac
    case "$rel" in
      src/*|tests/*) CHANGED_DATA_FILES_TYPED+=("$rel") ;;
    esac
  done
}

# --- CLI entry when executed (not sourced) ---
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  set -euo pipefail

  if [ "${1-}" != "" ]; then
    CHANGED_DATA_FILES_RANGE="$1"
  else
    changed_data_files_resolve_range
  fi
  changed_data_files_collect "$CHANGED_DATA_FILES_RANGE"

  echo "range=${CHANGED_DATA_FILES_RANGE}"
  echo "python:"
  printf '%s\n' "${CHANGED_DATA_FILES_PY[@]+"${CHANGED_DATA_FILES_PY[@]}"}"
  echo "sql:"
  printf '%s\n' "${CHANGED_DATA_FILES_SQL[@]+"${CHANGED_DATA_FILES_SQL[@]}"}"
  echo "typed:"
  printf '%s\n' "${CHANGED_DATA_FILES_TYPED[@]+"${CHANGED_DATA_FILES_TYPED[@]}"}"
fi
