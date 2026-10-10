# The deploy job's credentials (Day 35): GitHub's OIDC provider and one role, jobmatch-deploy, that
# only main of this repository can assume. In the main root, so terraform destroy removes them. If
# the account already has GitHub's provider (deploy-preflight.sh says), import it instead.
# The trust and the statements are literals, read through the deploy output by infra-checks.py
# (C35.5); the role's documents are built from them, so the two cannot differ.
locals {
  deploy = {
    role_name = "jobmatch-deploy"
    trust = {
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:sub" = "repo:Yusuprozimemet/jobmatch-microservices:ref:refs/heads/main"
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        }
      }
    }
    migrate_tasks = ["identity-service-migrate", "application-service-migrate"]
    statements = [
      { actions = ["ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:BatchGetImage"], resources = ["repositories"] },
      { actions = ["ecr:GetAuthorizationToken"], resources = ["*"] },
      { actions = ["ecs:UpdateService", "ecs:DescribeServices"], resources = ["services"] },
      { actions = ["ecs:RunTask"], resources = ["migrate-task-definitions"] },
      { actions = ["ecs:DescribeTasks"], resources = ["cluster-tasks"] },
      { actions = ["iam:PassRole"], resources = ["migrate-roles"] },
      { actions = ["logs:GetLogEvents"], resources = ["migrate-log-streams"] },
    ]
  }

  # Each resource key's ARNs; unknown until apply, so not in the output.
  deploy_resources = {
    "*"                      = ["*"]
    repositories             = [for repo in aws_ecr_repository.image : repo.arn]
    services                 = [for svc in module.service : svc.service_arn]
    migrate-task-definitions = [for name in local.deploy.migrate_tasks : module.task[name].task_definition_arn]
    cluster-tasks            = ["${replace(aws_ecs_cluster.main.arn, ":cluster/", ":task/")}/*"]
    migrate-roles            = flatten([for name in local.deploy.migrate_tasks : module.task[name].role_arns])
    migrate-log-streams      = [for name in local.deploy.migrate_tasks : "${module.task[name].log_group_arn}:log-stream:*"]
  }
}

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

resource "aws_iam_role" "deploy" {
  name = local.deploy.role_name
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [merge(local.deploy.trust, { Effect = "Allow", Principal = { Federated = aws_iam_openid_connect_provider.github.arn } })]
  })
}

resource "aws_iam_role_policy" "deploy" {
  name = "deploy"
  role = aws_iam_role.deploy.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [for s in local.deploy.statements : {
      Effect   = "Allow"
      Action   = s.actions
      Resource = flatten([for key in s.resources : local.deploy_resources[key]])
    }]
  })
}
