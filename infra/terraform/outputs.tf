output "services" {
  description = "Each service's declaration from locals.tf, read by infra-checks.py"
  value       = local.services
}

output "tasks" {
  description = "Each one-off task's declaration from locals.tf, read by infra-checks.py"
  value       = local.tasks
}

output "collector" {
  description = "The collector sidecar's declaration from collector.tf, read by infra-checks.py"
  value       = local.collector
}

output "dashboard" {
  description = "The dashboard's widgets from dashboard.tf, read by infra-checks.py"
  value       = local.dashboard
}

output "deploy" {
  description = "The deploy role's trust and statements from deploy.tf, read by infra-checks.py"
  value       = local.deploy
}

output "deploy_role_arn" {
  description = "The value for the repository variable AWS_DEPLOY_ROLE_ARN"
  value       = aws_iam_role.deploy.arn
}
