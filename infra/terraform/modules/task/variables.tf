variable "name" {
  type = string
}

variable "task" {
  type = object({
    cpu              = number
    memory           = number
    command          = optional(list(string), [])
    environment      = optional(map(string), {})
    environment_from = optional(map(string), {})
    secrets          = optional(map(string), {})
  })
}

variable "image" {
  type = string
}

variable "region" {
  type = string
}

variable "repository_arn" {
  type = string
}

# Each secret the task names, by the name its secrets map uses: the Secrets Manager secrets and the
# RDS master secret, which Terraform does not create.
variable "secret_arns_by_name" {
  type = map(string)
}

# The value behind each key that the task's environment_from names.
variable "environment_values" {
  type = map(string)
}
