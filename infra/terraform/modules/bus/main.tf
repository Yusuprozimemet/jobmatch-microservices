# Event bus: the user-deleted SNS topic, fanning out to SQS queues per consumer with
# dead-letter queues and depth alarms (scripts/bus-init/10-user-deleted.sh in compose,
# support/EventBus.java in the harness).

resource "aws_sns_topic" "user_deleted" {
  name = var.topic_name
}

resource "aws_sqs_queue" "dlq" {
  for_each                  = toset(var.queue_names)
  name                      = "${each.key}-dlq"
  message_retention_seconds = var.dlq_retention_seconds
}

resource "aws_sqs_queue" "queue" {
  for_each = toset(var.queue_names)
  name     = each.key
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = var.max_receive_count
  })
}

resource "aws_sqs_queue_policy" "queue" {
  for_each  = toset(var.queue_names)
  queue_url = aws_sqs_queue.queue[each.key].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "sns.amazonaws.com"
        }
        Action   = "sqs:SendMessage"
        Resource = aws_sqs_queue.queue[each.key].arn
        Condition = {
          ArnEquals = {
            "aws:SourceArn" = aws_sns_topic.user_deleted.arn
          }
        }
      }
    ]
  })
}

resource "aws_sns_topic_subscription" "queue" {
  for_each             = toset(var.queue_names)
  topic_arn            = aws_sns_topic.user_deleted.arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.queue[each.key].arn
  raw_message_delivery = true
}

resource "aws_sns_topic" "alarms" {
  name = "${var.topic_name}-dlq-alarms"
}

resource "aws_sns_topic_subscription" "alarm_email" {
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

resource "aws_cloudwatch_metric_alarm" "dlq" {
  for_each            = toset(var.queue_names)
  alarm_name          = "${each.key}-dlq-messages"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  treat_missing_data  = "notBreaching"

  dimensions = {
    QueueName = aws_sqs_queue.dlq[each.key].name
  }
}
