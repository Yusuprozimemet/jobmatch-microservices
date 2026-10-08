output "table_name" {
  value = aws_dynamodb_table.scores.name
}

output "table_arn" {
  value = aws_dynamodb_table.scores.arn
}
