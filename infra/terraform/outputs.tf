output "services" {
  description = "Each service's declaration from locals.tf, read by infra-checks.py"
  value       = local.services
}

output "tasks" {
  description = "Each one-off task's declaration from locals.tf, read by infra-checks.py"
  value       = local.tasks
}
