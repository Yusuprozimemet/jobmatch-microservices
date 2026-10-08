output "topic_arn" {
  value = aws_sns_topic.user_deleted.arn
}

output "queue_urls" {
  value = { for k, q in aws_sqs_queue.queue : k => q.id }
}

output "dlq_urls" {
  value = { for k, q in aws_sqs_queue.dlq : k => q.id }
}

output "alarm_topic_arn" {
  value = aws_sns_topic.alarms.arn
}
