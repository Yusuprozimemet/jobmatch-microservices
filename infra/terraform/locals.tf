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
      health_check  = local.jvm_health_check
      collector     = true

      environment = merge(local.telemetry_environment, {
        DB_PORT          = "5432"
        DB_NAME          = "identity_db"
        DB_USER          = "app_user"
        DB_IDENTITY_USER = "identity_user"

        APP_INTERNAL_TRUSTEDISSUERS_0_NAME      = "jobmatch-matching-service"
        APP_INTERNAL_TRUSTEDISSUERS_0_KEYSETURL = "http://matching-service:8080/.well-known/service-jwks.json"
        APP_INTERNAL_TRUSTEDISSUERS_1_NAME      = "jobmatch-application-service"
        APP_INTERNAL_TRUSTEDISSUERS_1_KEYSETURL = "http://application-service:8080/.well-known/service-jwks.json"

        # Behind the ALB over HTTPS: the session cookie is Secure and links use the domain.
        SESSION_COOKIE_SECURE = "true"
        APP_BASE_URL          = "https://${var.domain}"

        # The one-off migrate task runs the migrations, not the service.
        MIGRATE_ON_START = "false"
      })

      environment_from = {
        DB_HOST                       = "database-address"
        EVENTS_USER_DELETED_TOPIC_ARN = "user-deleted-topic"
      }

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
      health_check  = local.jvm_health_check
      collector     = true

      environment = merge(local.telemetry_environment, {
        DB_PORT      = "5432"
        DB_NAME      = "jobs_db"
        DB_JOBS_USER = "jobs_user"

        INTERNAL_APPLICATIONS_URL = "http://application-service:8080"

        APP_INTERNAL_TRUSTEDISSUERS_0_NAME      = "jobmatch-matching-service"
        APP_INTERNAL_TRUSTEDISSUERS_0_KEYSETURL = "http://matching-service:8080/.well-known/service-jwks.json"
        APP_INTERNAL_TRUSTEDISSUERS_1_NAME      = "jobmatch-application-service"
        APP_INTERNAL_TRUSTEDISSUERS_1_KEYSETURL = "http://application-service:8080/.well-known/service-jwks.json"
      })

      environment_from = {
        DB_HOST = "database-address"
      }

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
      health_check  = local.jvm_health_check
      collector     = true

      environment = merge(local.telemetry_environment, {
        AWS_REGION            = var.region
        INTERNAL_IDENTITY_URL = "http://identity-service:8080"
        INTERNAL_JOBS_URL     = "http://job-service:8080"
        LLM_BASE_URL          = "https://generativelanguage.googleapis.com/v1beta/openai"
        LLM_MODEL             = "gemini-flash-lite-latest"

        # Set here rather than left to the default: a profile change reaches the ranking within this window.
        PROFILE_CACHE_WINDOW = "10s"
      })

      # Scales on CPU. Its profile cache is per task, so PROFILE_CACHE_WINDOW bounds how stale a task is.
      scaling = { min_capacity = 1, max_capacity = 3, cpu_target = 70 }

      environment_from = {
        EVENTS_USER_DELETED_QUEUE_URL = "matching-user-deleted-url"
      }

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
      health_check  = local.jvm_health_check
      collector     = true

      environment = merge(local.telemetry_environment, {
        DB_PORT              = "5432"
        DB_NAME              = "apps_db"
        DB_APPLICATIONS_USER = "applications_user"

        INTERNAL_IDENTITY_URL   = "http://identity-service:8080"
        INTERNAL_JOBS_URL       = "http://job-service:8080"
        JOB_SERVICE_KEY_SET_URL = "http://job-service:8080/.well-known/service-jwks.json"

        EVENTS_CONSUMER_ENABLED = "true"

        # The one-off migrate task runs the migrations, not the service.
        MIGRATE_ON_START = "false"
      })

      environment_from = {
        DB_HOST                       = "database-address"
        EVENTS_USER_DELETED_QUEUE_URL = "applications-user-deleted-url"
      }

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
      # One task: RateLimit counts in memory, and plan.md keeps one gateway task.
      port          = 8081
      cpu           = 512
      memory        = 1024
      desired_count = 1
      health_check  = local.jvm_health_check
      collector     = true

      environment = merge(local.telemetry_environment, {
        IDENTITY_SERVICE_URL    = "http://identity-service:8080"
        JOB_SERVICE_URL         = "http://job-service:8080"
        MATCHING_SERVICE_URL    = "http://matching-service:8080"
        APPLICATION_SERVICE_URL = "http://application-service:8080"

        # The ALB and the frontend run in the public subnets, not the private ones: RateLimit trusts a
        # forwarded client address only when the connecting address matches this.
        GATEWAY_TRUSTED_PROXIES = "10\\.0\\.([0-9]|[1-9][0-9]|1[01][0-9]|12[0-7])\\.[0-9]{1,3}"
      })

      secrets     = {}
      task_policy = []
    }

    frontend = {
      port          = 3000
      cpu           = 256
      memory        = 512
      desired_count = 1
      collector     = false

      environment = {
        BACKEND_API_URL = "http://api-gateway:8081"
      }

      secrets     = {}
      task_policy = []
    }
  }
}

# The compose probe on the management port, for the five JVM services. The frontend has none here:
# its target group checks "/".
locals {
  jvm_health_check = {
    command      = ["CMD", "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/9090 && printf 'GET /actuator/health/readiness HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]
    interval     = 10
    timeout      = 5
    retries      = 5
    start_period = 60
  }
}

# The one-off tasks, declared as the services are and read the same way. Each runs once, before the
# services start (Day 35, H36.3), and has no service. A migrate task runs its service's image with
# MIGRATE_ONLY, which stops after the migrations.
locals {
  tasks = {
    identity-service-migrate = {
      image  = "identity-service"
      cpu    = 512
      memory = 1024

      environment = {
        DB_PORT          = "5432"
        DB_NAME          = "identity_db"
        DB_USER          = "app_user"
        DB_IDENTITY_USER = "identity_user"
        MIGRATE_ONLY     = "true"
      }

      environment_from = {
        DB_HOST = "database-address"
      }

      secrets = {
        DB_PASSWORD          = "db-password-app_user"
        DB_IDENTITY_PASSWORD = "db-password-identity_user"
      }
    }

    application-service-migrate = {
      image  = "application-service"
      cpu    = 512
      memory = 1024

      environment = {
        DB_PORT              = "5432"
        DB_NAME              = "apps_db"
        DB_APPLICATIONS_USER = "applications_user"
        MIGRATE_ONLY         = "true"
      }

      environment_from = {
        DB_HOST = "database-address"
      }

      secrets = {
        DB_APPLICATIONS_PASSWORD = "db-password-applications_user"
      }
    }

    # Each role's password comes from its own secret, not the command line (--passwords-from-env).
    db-setup = {
      image   = "db-setup"
      cpu     = 256
      memory  = 512
      command = ["--passwords-from-env"]

      environment = {
        POSTGRES_PORT = "5432"
        POSTGRES_USER = "jobmatch_admin"
      }

      environment_from = {
        POSTGRES_HOST = "database-address"
      }

      # The master's password is one key of the RDS-managed secret, not a secret Terraform creates.
      secrets = merge({ POSTGRES_PASSWORD = "rds-master:password::" }, { for role in local.db_roles : "DB_PASSWORD_${upper(role)}" => "db-password-${role}" })
    }
  }
}
