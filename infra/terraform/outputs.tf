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
