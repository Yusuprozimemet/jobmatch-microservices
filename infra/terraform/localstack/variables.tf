variable "region" {
  type    = string
  default = "eu-west-1"
}

variable "endpoint" {
  type    = string
  default = "http://localhost:4566"
}

variable "alert_email" {
  type        = string
  default     = "alerts@example.com"
  description = "Email address that receives the dead-letter queue alarms"
}
