output "task_definition_arn" {
  value = aws_ecs_task_definition.task.arn
}
output "role_arns" {
  value = [aws_iam_role.execution.arn, aws_iam_role.task.arn]
}
output "log_group_arn" {
  value = aws_cloudwatch_log_group.task.arn
}
