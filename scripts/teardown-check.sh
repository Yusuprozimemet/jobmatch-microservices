#!/usr/bin/env bash
# Lists what is left in the account and region that still bills after the main root's destroy
# (Day 35, C35.21). Each kind with anything left prints `left: <kind>: <names>`, and the last line
# counts the kinds. A failed aws call exits 1: an error is not "nothing left". Deliberately not
# listed, because they stay: the bootstrap's state bucket and budget, and X-Ray's traces (30 days,
# free, not deletable).
#
#   usage: scripts/teardown-check.sh
#
# Exits 0 only when nothing is left.
set -uo pipefail

usage() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
}

case "${1:-}" in
    "") ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
esac

export MSYS_NO_PATHCONV=1  # Git Bash would rewrite the /jobmatch prefixes as Windows paths
region="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-west-1}}"
kinds=0
found=""

look() {
    # look <aws arguments>: sets $found to the names the call answers, empty for none
    local out
    if ! out=$(aws "$@" --region "$region" --output text 2>&1); then
        echo "teardown-check: aws $* failed: $out" >&2
        exit 1
    fi
    found=$(printf '%s\n' "$out" | tr '\t' ' ' | sed 's/^ *//; s/ *$//' | grep -v '^$' | paste -sd' ' -)
    [ "$found" = None ] && found=""
    return 0
}

left() {
    # left <kind>: prints the kind's line when $found names something, and counts it
    [ -n "$found" ] || return 0
    echo "left: $1: $found"
    kinds=$((kinds + 1))
}

show() {
    # show <kind> <aws arguments>: look, then left
    local kind="$1"
    shift
    look "$@"
    left "$kind"
}

look ecs list-clusters --query clusterArns
clusters=$found
left "ECS clusters"
for cluster in $clusters; do
    show "ECS services" ecs list-services --cluster "$cluster" --query serviceArns
    show "ECS tasks" ecs list-tasks --cluster "$cluster" --query taskArns
done
show "load balancers" elbv2 describe-load-balancers --query 'LoadBalancers[].LoadBalancerName'
show "target groups" elbv2 describe-target-groups --query 'TargetGroups[].TargetGroupName'
show "RDS instances" rds describe-db-instances --query 'DBInstances[].DBInstanceIdentifier'
show "RDS snapshots" rds describe-db-snapshots --query 'DBSnapshots[].DBSnapshotIdentifier'
show "RDS automated backups" rds describe-db-instance-automated-backups \
    --query 'DBInstanceAutomatedBackups[].DBInstanceIdentifier'
show "Elastic IPs" ec2 describe-addresses --query 'Addresses[].PublicIp'
show "non-default VPCs" ec2 describe-vpcs --filters Name=is-default,Values=false --query 'Vpcs[].VpcId'
show "network interfaces" ec2 describe-network-interfaces --query 'NetworkInterfaces[].NetworkInterfaceId'
show "ECR repositories" ecr describe-repositories --query 'repositories[].repositoryName'
show "secrets" secretsmanager list-secrets --query 'SecretList[].Name'
show "DynamoDB tables" dynamodb list-tables --query TableNames
show "SNS topics" sns list-topics --query 'Topics[].TopicArn'
show "SQS queues" sqs list-queues --query QueueUrls
show "CloudWatch alarms" cloudwatch describe-alarms --query 'MetricAlarms[].AlarmName'
show "CloudWatch dashboards" cloudwatch list-dashboards --query 'DashboardEntries[].DashboardName'
groups=""
for prefix in /ecs/jobmatch /jobmatch; do
    look logs describe-log-groups --log-group-name-prefix "$prefix" --query 'logGroups[].logGroupName'
    groups="$groups $found"
done
found=$(echo $groups)
left "log groups"
show "IAM OIDC providers" iam list-open-id-connect-providers --query 'OpenIDConnectProviderList[].Arn'

if [ "$kinds" -eq 0 ]; then
    echo "teardown-check: nothing left"
    exit 0
fi
echo "teardown-check: $kinds kinds left"
exit 1
