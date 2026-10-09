# The one declaration of each service. module.service reads it, and the services output returns
# it so infra-checks.py can read it: task definitions and policies are unknown in a plan without
# an account, so the values must be literals.
locals {
  services = {
    identity-service = {
      port          = 8080
      cpu           = 512
      memory        = 1024
      desired_count = 1
      environment   = {}
    }
    job-service = {
      port          = 8080
      cpu           = 512
      memory        = 1024
      desired_count = 1
      environment   = {}
    }
    matching-service = {
      port          = 8080
      cpu           = 512
      memory        = 1024
      desired_count = 1
      environment   = {}
    }
    application-service = {
      port          = 8080
      cpu           = 512
      memory        = 1024
      desired_count = 1
      environment   = {}
    }
    api-gateway = {
      port          = 8081
      cpu           = 512
      memory        = 1024
      desired_count = 1
      environment   = {}
    }
    frontend = {
      port          = 3000
      cpu           = 256
      memory        = 512
      desired_count = 1
      environment   = {}
    }
  }
}
