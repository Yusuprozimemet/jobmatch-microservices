variable "name" {
  type = string
}

variable "service" {
  type = object({
    port          = number
    cpu           = number
    memory        = number
    desired_count = number
    environment   = optional(map(string), {})
    secrets       = optional(map(string), {})

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
  })
}

variable "cluster_id" {
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
