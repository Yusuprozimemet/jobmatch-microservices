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
