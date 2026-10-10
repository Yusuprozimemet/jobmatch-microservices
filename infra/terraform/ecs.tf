resource "aws_ecs_cluster" "main" {
  name = "jobmatch"
}

# Service Connect names, so every internal URL keeps its compose form.
resource "aws_service_discovery_http_namespace" "main" {
  name = "jobmatch"
}

# Tasks run in the public subnets with a public IP because Day 32 chose no NAT gateway.
module "service" {
  for_each = local.services
  source   = "./modules/service"

  name               = each.key
  service            = each.value
  cluster_id         = aws_ecs_cluster.main.id
  cluster_name       = aws_ecs_cluster.main.name
  image              = "${aws_ecr_repository.image[each.key].repository_url}:${var.image_tag}"
  repository_arn     = aws_ecr_repository.image[each.key].arn
  secret_arns        = { for env, name in each.value.secrets : env => aws_secretsmanager_secret.secret[name].arn }
  subnet_ids         = module.network.public_subnet_ids
  security_group_ids = [module.network.tasks_security_group_id]
  region             = var.region
  namespace_arn      = aws_service_discovery_http_namespace.main.arn

  # The frontend only calls out, so it is a Service Connect client and is not registered.
  register = each.key != "frontend"

  # The five JVM services run a collector beside them; the frontend exports nothing.
  collector = each.value.collector ? local.collector : null

  # Through the listener, so the service waits until the target group is attached to the ALB:
  # ECS rejects a target group with no load balancer.
  target_group_arns = each.key == "frontend" ? [aws_lb_listener.https.default_action[0].target_group_arn] : []

  # The ARN behind each key that a service's task_policy names.
  policy_resources = {
    user-deleted-topic        = module.bus.topic_arn
    scores-table              = module.scores.table_arn
    matching-user-deleted     = module.bus.queue_arns["matching-user-deleted"]
    applications-user-deleted = module.bus.queue_arns["applications-user-deleted"]
  }

  # The value behind each key that a service's environment_from names.
  environment_values = {
    database-address              = module.database.address
    user-deleted-topic            = module.bus.topic_arn
    matching-user-deleted-url     = module.bus.queue_urls["matching-user-deleted"]
    applications-user-deleted-url = module.bus.queue_urls["applications-user-deleted"]
  }
}

# The one-off tasks run before the services (Day 35, H36.3), so they have no aws_ecs_service. The
# RDS master secret is not in aws_secretsmanager_secret.secret: RDS creates and owns it.
module "task" {
  for_each = local.tasks
  source   = "./modules/task"

  name           = each.key
  task           = each.value
  image          = "${aws_ecr_repository.image[each.value.image].repository_url}:${var.image_tag}"
  repository_arn = aws_ecr_repository.image[each.value.image].arn
  region         = var.region

  secret_arns_by_name = merge({ for name, secret in aws_secretsmanager_secret.secret : name => secret.arn }, { rds-master = module.database.master_user_secret_arn })

  environment_values = {
    database-address = module.database.address
  }
}
