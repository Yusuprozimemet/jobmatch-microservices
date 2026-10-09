variable "region" {
  type    = string
  default = "eu-west-1"
}

variable "availability_zones" {
  type    = list(string)
  default = ["eu-west-1a", "eu-west-1b"]
}

variable "alert_email" {
  type        = string
  description = "Email address that receives the dead-letter queue alarms"
}

variable "image_tag" {
  type        = string
  default     = "latest"
  description = "Tag of every service image in ECR; Day 35's deploy sets it"
}

variable "domain" {
  type        = string
  description = "Public domain the ALB serves; its certificate is validated in route53_zone_id"
}

variable "route53_zone_id" {
  type        = string
  description = "Route 53 hosted zone that holds domain"
}
