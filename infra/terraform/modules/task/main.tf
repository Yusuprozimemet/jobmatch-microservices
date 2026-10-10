resource "aws_cloudwatch_log_group" "task" {
  name              = "/ecs/jobmatch/${var.name}"
  retention_in_days = 14
}

# A secret reference is a secret's name, or name:json-key:: for one key of a JSON secret. The IAM
# policy names the plain ARNs, so only valueFrom carries the key suffix.
locals {
  secret_value_from = { for env, ref in var.task.secrets : env => "${var.secret_arns_by_name[split(":", ref)[0]]}${trimprefix(ref, split(":", ref)[0])}" }
  secret_names      = distinct([for ref in values(var.task.secrets) : split(":", ref)[0]])
}

resource "aws_ecs_task_definition" "task" {
  family                   = "jobmatch-${var.name}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.task.cpu)
  memory                   = tostring(var.task.memory)
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  # Only db-setup passes arguments and only jobs-seed an entry point, so each key is merged in only when set.
  container_definitions = jsonencode([merge({
    name        = var.name
    image       = var.image
    essential   = true
    environment = [for k, v in merge(var.task.environment, { for env, key in var.task.environment_from : env => var.environment_values[key] }) : { name = k, value = v }]
    secrets     = [for env, arn in local.secret_value_from : { name = env, valueFrom = arn }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.task.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = var.name
      }
    }
    }, length(var.task.entry_point) == 0 ? {} : {
    entryPoint = var.task.entry_point
    }, length(var.task.command) == 0 ? {} : {
    command = var.task.command
  })])
}

# Every task gets both roles, as in modules/service; this module gives the task role no policy.
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
    resources = ["${aws_cloudwatch_log_group.task.arn}:*"]
  }
}

resource "aws_iam_role_policy" "execution" {
  role   = aws_iam_role.execution.name
  policy = data.aws_iam_policy_document.execution.json
}

# A separate policy, as in modules/service: it lists only the secrets this task names.
resource "aws_iam_role_policy" "secrets" {
  count = length(var.task.secrets) > 0 ? 1 : 0

  role = aws_iam_role.execution.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [for name in local.secret_names : var.secret_arns_by_name[name]]
    }]
  })
}
