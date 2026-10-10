variable "name" {
  type = string
}

variable "service" {
  type = object({
    port             = number
    cpu              = number
    memory           = number
    desired_count    = number
    environment      = optional(map(string), {})
    environment_from = optional(map(string), {})
    secrets          = optional(map(string), {})
    collector        = optional(bool, false)

    task_policy = optional(list(object({
      actions   = list(string)
      resources = list(string)
    })), [])

    health_check = optional(object({
      command      = list(string)
      interval     = number
      timeout      = number
      retries      = number
      start_period = number
    }))

    scaling = optional(object({
      min_capacity = number
      max_capacity = number
      cpu_target   = number
    }))
  })
}

variable "cluster_id" {
  type = string
}

variable "cluster_name" {
  type = string
}

variable "image" {
  type = string
}

variable "subnet_ids" {
  type = list(string)
}

variable "security_group_ids" {
  type = list(string)
}

variable "region" {
  type = string
}

variable "repository_arn" {
  type = string
}

variable "secret_arns" {
  type = map(string)
}

variable "policy_resources" {
  type = map(string)
}

# A list, not a nullable ARN: its length is known at plan time, so the plan shows the block.
variable "target_group_arns" {
  type    = list(string)
  default = []
}

variable "namespace_arn" {
  type = string
}

variable "register" {
  type = bool
}

# The value behind each key that a service's environment_from names.
variable "environment_values" {
  type = map(string)
}

# The collector beside a JVM service's container, from collector.tf. Null leaves the task with one
# container: the frontend and the one-off tasks have none.
variable "collector" {
  type = object({
    image             = string
    essential         = bool
    restart           = bool
    memory            = number
    log_stream_prefix = string
    environment       = map(string)
  })
  default = null
}
