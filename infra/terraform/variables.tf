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
  description = "Tag of every service image in ECR; stays latest: the deploy job pushes latest and forces a new deployment, so no task definition changes"
}

variable "start_services" {
  type        = bool
  default     = false
  description = "Whether the services run: false sets every desired count to 0 and leaves out the scaling, so the first apply starts nothing before the images and the one-off tasks"
}

variable "domain" {
  type        = string
  default     = ""
  description = "Public domain the ALB serves; its certificate is validated in route53_zone_id. Empty: a self-signed certificate imported to ACM serves the ALB's DNS name"

  validation {
    condition     = var.domain == "" || var.route53_zone_id != ""
    error_message = "A domain needs route53_zone_id: the certificate is validated in that zone."
  }
}

variable "route53_zone_id" {
  type        = string
  default     = ""
  description = "Route 53 hosted zone that holds domain. Empty when domain is empty"
}
