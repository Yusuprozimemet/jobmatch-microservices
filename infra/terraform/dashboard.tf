# The one CloudWatch dashboard Day 35 watches (Day 34). Its widgets are a literal local, so
# infra-checks.py reads them from the dashboard output; the ALB's arn_suffix is computed, so the
# resource merges it into the ALB rows.

locals {
  dashboard = [
    {
      type   = "metric"
      width  = 12
      height = 6
      properties = {
        title   = "ALB requests"
        stat    = "Sum"
        period  = 60
        view    = "timeSeries"
        metrics = [["AWS/ApplicationELB", "RequestCount"]]
      }
    },
    {
      type   = "metric"
      width  = 12
      height = 6
      properties = {
        title   = "ALB 5xx"
        stat    = "Sum"
        period  = 300
        view    = "timeSeries"
        metrics = [["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count"], ["AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count"]]
      }
    },
    {
      type   = "metric"
      width  = 12
      height = 6
      properties = {
        title   = "ALB target response time"
        stat    = "Average"
        period  = 60
        view    = "timeSeries"
        metrics = [["AWS/ApplicationELB", "TargetResponseTime"]]
      }
    },
    {
      type   = "metric"
      width  = 12
      height = 6
      properties = {
        title   = "Request rate by service"
        stat    = "SampleCount"
        period  = 60
        view    = "timeSeries"
        metrics = [for name, s in local.services : ["JobMatch", "http.server.requests", "service.name", "jobmatch-${name}"] if s.collector]
      }
    },
    {
      type   = "metric"
      width  = 12
      height = 6
      properties = {
        title   = "Request latency by service"
        stat    = "Average"
        period  = 60
        view    = "timeSeries"
        metrics = [for name, s in local.services : ["JobMatch", "http.server.requests", "service.name", "jobmatch-${name}"] if s.collector]
      }
    },
    {
      type   = "metric"
      width  = 12
      height = 6
      properties = {
        title   = "ECS CPU"
        stat    = "Average"
        period  = 300
        view    = "timeSeries"
        metrics = [for name in keys(local.services) : ["AWS/ECS", "CPUUtilization", "ClusterName", "jobmatch", "ServiceName", name]]
      }
    },
    {
      type   = "metric"
      width  = 12
      height = 6
      properties = {
        title   = "ECS memory"
        stat    = "Average"
        period  = 300
        view    = "timeSeries"
        metrics = [for name in keys(local.services) : ["AWS/ECS", "MemoryUtilization", "ClusterName", "jobmatch", "ServiceName", name]]
      }
    },
    {
      type   = "metric"
      width  = 12
      height = 6
      properties = {
        title   = "DLQ depth"
        stat    = "Maximum"
        period  = 300
        view    = "timeSeries"
        metrics = [["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", "applications-user-deleted-dlq"], ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", "matching-user-deleted-dlq"]]
      }
    },
  ]
}

resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = "jobmatch"
  dashboard_body = jsonencode({
    widgets = [for w in local.dashboard : merge(w, {
      properties = merge(w.properties, {
        region  = var.region
        metrics = [for m in w.properties.metrics : m[0] == "AWS/ApplicationELB" ? concat(m, ["LoadBalancer", aws_lb.main.arn_suffix]) : m]
      })
    })]
  })
}
