output "endpoint" {
  value = aws_db_instance.main.endpoint
}

output "address" {
  value       = aws_db_instance.main.address
  description = "the host without the port"
}

output "master_user_secret_arn" {
  value = aws_db_instance.main.master_user_secret[0].secret_arn
}
