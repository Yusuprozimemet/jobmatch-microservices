#!/bin/bash
# The user-deleted topic and queues for the event bus (Day 26): SNS topic fanning out to
# SQS queues per consumer, each with a dead-letter queue and redrive policy. LocalStack runs
# this from /etc/localstack/init/ready.d once the emulator is ready; the harness creates the
# same resources with the SDK in support/EventBus.java. The dead-letter queues keep messages
# 14 days, as Terraform's bus module does.
set -euo pipefail

topic_arn=$(awslocal sns create-topic --name user-deleted --query TopicArn --output text)

for queue in applications-user-deleted matching-user-deleted; do
  dlq="${queue}-dlq"
  dlq_url=$(awslocal sqs create-queue --queue-name "$dlq" --attributes MessageRetentionPeriod=1209600 --query QueueUrl --output text)
  dlq_arn=$(awslocal sqs get-queue-attributes --queue-url "$dlq_url" --attribute-names QueueArn --query Attributes.QueueArn --output text)

  queue_url=$(awslocal sqs create-queue --queue-name "$queue" --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}" --query QueueUrl --output text)
  queue_arn=$(awslocal sqs get-queue-attributes --queue-url "$queue_url" --attribute-names QueueArn --query Attributes.QueueArn --output text)

  awslocal sns subscribe --topic-arn "$topic_arn" --protocol sqs --notification-endpoint "$queue_arn" --attributes RawMessageDelivery=true
done
