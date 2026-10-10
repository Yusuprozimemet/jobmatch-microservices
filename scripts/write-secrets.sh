#!/usr/bin/env bash
# Writes the fourteen secret values in infra/terraform/secrets.tf to Secrets Manager (Day 35).
# The values are files in one directory, named after their secret. The seven role passwords and
# the four signing keys are made there when missing. The three from the maintainer (the Google
# client ID and secret, the LLM key) must already be there: a missing one stops the script before
# anything is written. The directory must be outside the repository. Values go to AWS as file://,
# off the command line, and only names are printed.
#
#   usage: scripts/write-secrets.sh <dir>
set -euo pipefail

usage() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
}

case "${1:-}" in
    "") usage >&2; exit 2 ;;
    -h | --help) usage; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
region="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-west-1}}"
names=(
    db-password-app_user db-password-analytics_user db-password-analytics_dev_user
    db-password-identity_user db-password-applications_user db-password-matching_user
    db-password-jobs_user jwt-private-key service-jwt-private-key-job-service
    service-jwt-private-key-matching-service service-jwt-private-key-application-service
    google-client-id google-client-secret llm-api-key
)

umask 077
[ -d "$1" ] || { echo "write-secrets: no directory $1" >&2; exit 2; }
dir="$(cd "$1" && pwd -P)"
root="$(cd "$REPO_ROOT" && pwd -P)"
case "$dir/" in
    "$root/"*) echo "write-secrets: $dir is inside the repository; keep the secrets outside it" >&2; exit 2 ;;
esac

missing=()
for name in google-client-id google-client-secret llm-api-key; do
    [ -s "$dir/$name" ] || missing+=("$name")
done
if [ "${#missing[@]}" -gt 0 ]; then
    echo "write-secrets: missing or empty in $dir: ${missing[*]}" >&2
    exit 1
fi

for name in "${names[@]}"; do
    case "$name" in
        db-password-*)
            # printf, not echo: a trailing newline would be part of the password
            [ -s "$dir/$name" ] || printf '%s' "$(openssl rand -hex 24)" > "$dir/$name" ;;
        jwt-private-key | service-jwt-private-key-*)
            # jwt-key.sh is not executable in the index, so it runs through sh
            sh "$REPO_ROOT/scripts/jwt-key.sh" "$dir/$name" > /dev/null ;;
        *)
            # An editor's trailing newline would become part of the value
            value="$(cat "$dir/$name")"; printf '%s' "$value" > "$dir/$name" ;;
    esac
done

# aws.exe does not read Git Bash's /c/ paths, so file:// gets the Windows form
file_dir="$dir"
if command -v cygpath > /dev/null; then
    file_dir="$(cygpath -m "$dir")"
fi
for name in "${names[@]}"; do
    aws secretsmanager put-secret-value --region "$region" --secret-id "jobmatch/$name" \
        --secret-string "file://$file_dir/$name" > /dev/null
    echo "wrote jobmatch/$name"
done
echo "write-secrets: ${#names[@]} secrets written"
