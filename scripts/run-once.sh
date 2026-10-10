#!/usr/bin/env bash
# Runs one of the one-off ECS tasks (infra/terraform/locals.tf `tasks`) and reports how it ended
# (Day 35). The tasks are the migrations and the seed. They run in the services' subnets and
# security group, read from the api-gateway service, which exists even at desired_count 0, so the
# role needs no ec2 permission (the same as the migrate step in identity-service-ci-cd.yaml). The
# exit code is the container's, and the last log lines print so a failed run says why.
#
#   usage: scripts/run-once.sh <db-setup|identity-service-migrate|application-service-migrate|jobs-seed>
#
# Exits 0 only when the container exited 0.
set -euo pipefail

usage() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
}

case "${1:-}" in
    "") usage >&2; exit 2 ;;
    -h | --help) usage; exit 0 ;;
esac

task="$1"
region="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-west-1}}"

network=$(aws ecs describe-services --region "$region" --cluster jobmatch --services api-gateway \
    --query 'services[0].networkConfiguration' --output json)
if [ -z "$network" ] || [ "$network" = null ]; then
    echo "run-once: the api-gateway service has no network configuration" >&2
    exit 1
fi

arn=$(aws ecs run-task --region "$region" --cluster jobmatch --task-definition "jobmatch-$task" \
    --launch-type FARGATE --network-configuration "$network" \
    --query 'tasks[0].taskArn' --output text)
aws ecs wait tasks-stopped --region "$region" --cluster jobmatch --tasks "$arn"
read -r code reason < <(aws ecs describe-tasks --region "$region" --cluster jobmatch --tasks "$arn" \
    --query 'tasks[0].[containers[0].exitCode, stoppedReason]' --output text)
aws logs get-log-events --region "$region" --log-group-name "/ecs/jobmatch/$task" \
    --log-stream-name "$task/$task/${arn##*/}" --limit 50 --query 'events[].message' --output text || true
echo "stopped reason: $reason"
echo "exit code: $code"
[ "$code" = 0 ]
