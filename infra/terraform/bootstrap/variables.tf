variable "region" {
  type    = string
  default = "eu-west-1"
}

variable "state_bucket_name" {
  type    = string
  default = "jobmatch-microservices-tfstate"
}

variable "budget_limit_usd" {
  type    = string
  default = "20"
}

variable "alert_email" {
  type        = string
  description = "Email address that receives the budget alert"
}
