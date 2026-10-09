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
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  # Only the JVM services have a probe, so the healthCheck key is merged in only when one is set.
  container_definitions = jsonencode([merge({
    name      = var.name
    image     = var.image
    essential = true
    portMappings = [{
      name          = var.name
      containerPort = var.service.port
      protocol      = "tcp"
    }]
    environment = [for k, v in var.service.environment : { name = k, value = v }]
    secrets     = [for env, arn in var.secret_arns : { name = env, valueFrom = arn }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.service.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = var.name
      }
    }
    }, var.service.health_check == null ? {} : {
    healthCheck = {
      command     = var.service.health_check.command
      interval    = var.service.health_check.interval
      timeout     = var.service.health_check.timeout
      retries     = var.service.health_check.retries
      startPeriod = var.service.health_check.start_period
    }
  })])
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

  dynamic "load_balancer" {
    for_each = var.target_group_arns

    content {
      target_group_arn = load_balancer.value
      container_name   = var.name
      container_port   = var.service.port
    }
  }
}

# Every task gets both roles; the task role has no policy unless its service names one.
data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "jobmatch-${var.name}-execution"
  assume_role_policy = data.aws_iam_policy_document.assume.json
}

resource "aws_iam_role" "task" {
  name               = "jobmatch-${var.name}-task"
  assume_role_policy = data.aws_iam_policy_document.assume.json
}

data "aws_iam_policy_document" "execution" {
  # ecr:GetAuthorizationToken takes no resource, so this is the one "*" in the execution role.
  statement {
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
    resources = [var.repository_arn]
  }

  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.service.arn}:*"]
  }
}

resource "aws_iam_role_policy" "execution" {
  role   = aws_iam_role.execution.name
  policy = data.aws_iam_policy_document.execution.json
}

# A separate policy: a statement with no resources is invalid, and the gateway and frontend have no secrets.
resource "aws_iam_role_policy" "secrets" {
  count = length(var.secret_arns) > 0 ? 1 : 0

  role = aws_iam_role.execution.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = values(var.secret_arns)
    }]
  })
}

resource "aws_iam_role_policy" "task" {
  count = length(var.service.task_policy) > 0 ? 1 : 0

  role = aws_iam_role.task.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [for s in var.service.task_policy : {
      Effect   = "Allow"
      Action   = s.actions
      Resource = [for key in s.resources : var.policy_resources[key]]
    }]
  })
}
