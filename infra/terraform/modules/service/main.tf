resource "aws_cloudwatch_log_group" "service" {
  name              = "/ecs/jobmatch/${var.name}"
  retention_in_days = 14
}

resource "aws_ecs_task_definition" "service" {
  family                   = "jobmatch-${var.name}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.service.cpu)
  memory                   = tostring(var.service.memory)

  container_definitions = jsonencode([{
    name      = var.name
    image     = var.image
    essential = true
    portMappings = [{
      name          = var.name
      containerPort = var.service.port
      protocol      = "tcp"
    }]
    environment = [for k, v in var.service.environment : { name = k, value = v }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.service.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = var.name
      }
    }
  }])
}

resource "aws_ecs_service" "service" {
  name            = var.name
  cluster         = var.cluster_id
  task_definition = aws_ecs_task_definition.service.arn
  launch_type     = "FARGATE"
  desired_count   = var.service.desired_count

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = var.security_group_ids
    assign_public_ip = true
  }
}
