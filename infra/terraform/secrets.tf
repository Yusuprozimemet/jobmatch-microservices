locals {
  # The seven roles in scripts/db-setup.py's ROLES.
  db_roles = ["app_user", "analytics_user", "analytics_dev_user", "identity_user", "applications_user", "matching_user", "jobs_user"]

  secret_names = concat([for role in local.db_roles : "db-password-${role}"], [
    "jwt-private-key",
    "service-jwt-private-key-job-service",
    "service-jwt-private-key-matching-service",
    "service-jwt-private-key-application-service",
    "google-client-id",
    "google-client-secret",
    "llm-api-key",
  ])
}

# No aws_secretsmanager_secret_version: the values are written on Day 35, so none reaches the state.
# recovery_window_in_days = 0 lets destroy delete them at once, as force_delete does for ECR.
resource "aws_secretsmanager_secret" "secret" {
  for_each = toset(local.secret_names)

  name                    = "jobmatch/${each.key}"
  recovery_window_in_days = 0
}
