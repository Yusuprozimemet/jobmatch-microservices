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
