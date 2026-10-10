#!/usr/bin/env bash
# Answers the five questions the main root's first apply depends on, before it runs (Day 35).
# Each answer is one line, ok or FAIL, with the question and the answer. All five always print,
# and the exit is 1 when any is FAIL. The var file defaults to infra/terraform/deploy.tfvars.
#
#   usage: scripts/deploy-preflight.sh [--var-file <path>]
set -uo pipefail

usage() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
var_file="$REPO_ROOT/infra/terraform/deploy.tfvars"
while [ $# -gt 0 ]; do
    case "$1" in
        -h | --help) usage; exit 0 ;;
        --var-file) var_file="${2:?--var-file needs a path}"; shift 2 ;;
        *) usage >&2; exit 2 ;;
    esac
done
[ -f "$var_file" ] || { echo "deploy-preflight: no var file at $var_file" >&2; exit 2; }

region="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-west-1}}"
fails=0

answer() {
    # answer <ok|FAIL> <question> <answer>: prints the line and counts a FAIL
    [ "$1" = ok ] || fails=$((fails + 1))
    printf '%-4s %s: %s\n' "$1" "$2" "$3"
}

tfvar() {
    sed -n 's/^ *'"$1"' *= *"\(.*\)" *$/\1/p' "$var_file"
}

default_of() {
    sed -n "/variable \"$1\"/,/^}/s/^ *default *= *\"\(.*\)\"/\1/p" \
        "$REPO_ROOT/infra/terraform/modules/database/variables.tf"
}

normalise() {
    # Lowercase, no trailing dots, sorted, on one line: two lists of name servers compare as text
    tr '[:space:]' '\n' | tr '[:upper:]' '[:lower:]' | sed 's/\.$//' | grep -v '^$' | sort | paste -sd' ' -
}

version=$(default_of engine_version)
class=$(default_of instance_class)
versions=$(aws rds describe-orderable-db-instance-options --region "$region" --engine postgres \
    --db-instance-class "$class" \
    --query "OrderableDBInstanceOptions[?starts_with(EngineVersion, '$version')].EngineVersion" \
    --output text)
if [ -n "$versions" ]; then
    answer ok "PostgreSQL $version orderable on $class" "$(printf '%s\n' $versions | head -3 | paste -sd, -)"
else
    answer FAIL "PostgreSQL $version orderable on $class" "none in $region"
fi

bucket=$(sed -n 's/^ *bucket *= *"\(.*\)"$/\1/p' "$REPO_ROOT/infra/terraform/versions.tf")
out=$(aws s3api head-bucket --region "$region" --bucket "$bucket" 2>&1)
rc=$?
if [ "$rc" -eq 0 ]; then
    answer ok "state bucket $bucket" "exists and is yours"
elif printf '%s' "$out" | grep -qE '404|Not Found'; then
    answer ok "state bucket $bucket" "free"
else
    answer FAIL "state bucket $bucket" "taken by another account"
fi

question="GitHub OIDC provider"
if ! providers=$(aws iam list-open-id-connect-providers --query 'OpenIDConnectProviderList[].Arn' --output text); then
    answer FAIL "$question" "aws iam list-open-id-connect-providers failed"
elif github=$(printf '%s\n' $providers | grep token.actions.githubusercontent.com); then
    answer ok "$question" "present; import it: terraform import aws_iam_openid_connect_provider.github $github"
else
    answer ok "$question" "absent; the apply creates it"
fi

email=$(tfvar alert_email)
if [ -n "$email" ] && [ "$email" != alerts@example.com ]; then
    answer ok alert_email "$email"
else
    answer FAIL alert_email "${email:-empty}: set a real address"
fi

domain=$(tfvar domain)
delegated=$(aws route53 get-hosted-zone --region "$region" --id "$(tfvar route53_zone_id)" \
    --query 'DelegationSet.NameServers' --output text)
if command -v dig > /dev/null; then
    public=$(dig +short NS "$domain")
else
    public=$(nslookup -type=NS "$domain" | sed -n 's/.*nameserver = //p')
fi
want=$(printf '%s\n' "$delegated" | normalise)
have=$(printf '%s\n' "$public" | normalise)
if [ -n "$want" ] && [ "$want" = "$have" ]; then
    answer ok "NS delegation for $domain" "$want"
else
    answer FAIL "NS delegation for $domain" "zone: $want; public: ${have:-none}"
fi

[ "$fails" -eq 0 ]
