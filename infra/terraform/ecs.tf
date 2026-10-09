resource "aws_ecs_cluster" "main" {
  name = "jobmatch"
}

# Tasks run in the public subnets with a public IP because Day 32 chose no NAT gateway.
module "service" {
  for_each = local.services
  source   = "./modules/service"

  name               = each.key
  service            = each.value
  cluster_id         = aws_ecs_cluster.main.id
  image              = "${aws_ecr_repository.image[each.key].repository_url}:${var.image_tag}"
  repository_arn     = aws_ecr_repository.image[each.key].arn
  secret_arns        = { for env, name in each.value.secrets : env => aws_secretsmanager_secret.secret[name].arn }
  subnet_ids         = module.network.public_subnet_ids
  security_group_ids = [module.network.tasks_security_group_id]
  region             = var.region

  # The ARN behind each key that a service's task_policy names.
  policy_resources = {
    user-deleted-topic        = module.bus.topic_arn
    scores-table              = module.scores.table_arn
    matching-user-deleted     = module.bus.queue_arns["matching-user-deleted"]
    applications-user-deleted = module.bus.queue_arns["applications-user-deleted"]
  }
}
