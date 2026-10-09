# The one declaration of each service. module.service reads it, and the services output returns
# it so infra-checks.py can read it: task definitions and policies are unknown in a plan without
# an account, so the values must be literals. Each service also names its secrets and the AWS
# actions its task role gets.
locals {
  services = {
    identity-service = {
      port          = 8080
      cpu           = 512
      memory        = 1024
      desired_count = 1
      environment   = {}

      secrets = {
        DB_PASSWORD          = "db-password-app_user"
        DB_IDENTITY_PASSWORD = "db-password-identity_user"
        JWT_PRIVATE_KEY      = "jwt-private-key"
        GOOGLE_CLIENT_ID     = "google-client-id"
        GOOGLE_CLIENT_SECRET = "google-client-secret"
      }

      task_policy = [{
        actions   = ["sns:Publish"]
        resources = ["user-deleted-topic"]
      }]
    }
    job-service = {
      port          = 8080
      cpu           = 512
      memory        = 1024
      desired_count = 1
      environment   = {}

      secrets = {
        DB_JOBS_PASSWORD        = "db-password-jobs_user"
        SERVICE_JWT_PRIVATE_KEY = "service-jwt-private-key-job-service"
      }

      task_policy = []
    }
    matching-service = {
      port          = 8080
      cpu           = 512
      memory        = 1024
      desired_count = 1
      environment   = {}

      secrets = {
        LLM_API_KEY             = "llm-api-key"
        SERVICE_JWT_PRIVATE_KEY = "service-jwt-private-key-matching-service"
      }

      task_policy = [
        {
          actions   = ["dynamodb:BatchGetItem", "dynamodb:BatchWriteItem", "dynamodb:Query"]
          resources = ["scores-table"]
        },
        {
          actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
          resources = ["matching-user-deleted"]
        },
      ]
    }
    application-service = {
      port          = 8080
      cpu           = 512
      memory        = 1024
      desired_count = 1
      environment   = {}

      secrets = {
        DB_APPLICATIONS_PASSWORD = "db-password-applications_user"
        SERVICE_JWT_PRIVATE_KEY  = "service-jwt-private-key-application-service"
      }

      task_policy = [{
        actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
        resources = ["applications-user-deleted"]
      }]
    }
    api-gateway = {
      port          = 8081
      cpu           = 512
      memory        = 1024
      desired_count = 1
      environment   = {}

      secrets     = {}
      task_policy = []
    }
    frontend = {
      port          = 3000
      cpu           = 256
      memory        = 512
      desired_count = 1
      environment   = {}

      secrets     = {}
      task_policy = []
    }
  }
}
