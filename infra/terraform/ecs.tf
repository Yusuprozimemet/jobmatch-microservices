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
  subnet_ids         = module.network.public_subnet_ids
  security_group_ids = [module.network.tasks_security_group_id]
  region             = var.region
}
