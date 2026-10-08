variable "topic_name" {
  type    = string
  default = "user-deleted"
}

variable "queue_names" {
  type    = list(string)
  default = ["applications-user-deleted", "matching-user-deleted"]
}

variable "max_receive_count" {
  type    = number
  default = 5
}

variable "dlq_retention_seconds" {
  type        = number
  default     = 1209600
  description = "14 days, the SQS maximum; the message holds only a userId"
}

variable "alert_email" {
  type        = string
  description = "Email address that receives the dead-letter queue alarms"
}
