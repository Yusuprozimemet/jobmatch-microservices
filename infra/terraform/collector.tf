# The ADOT collector each JVM service runs beside it (Day 34). infra-ci starts this image with
# collector.yaml, so a pin the configuration does not start on fails before any plan.

# Terraform owns the group awsemf writes to, so destroy leaves none the collector made. The name is
# a literal here and in collector.yaml's log_group_name.
resource "aws_cloudwatch_log_group" "metrics" {
  name              = "/jobmatch/metrics"
  retention_in_days = 14
}

locals {
  collector_image = "public.ecr.aws/aws-observability/aws-otel-collector:v0.50.0"

  # The collector each JVM service's task gets. Nothing in it is computed: an output with an unknown
  # part loses its value in the plan.
  collector = {
    image             = local.collector_image
    essential         = false # a collector crash does not stop the service
    restart           = true  # ECS restarts it (restartPolicy)
    memory            = 128   # hard limit inside the task's size; ~24 MiB idle (the Day 34 audit)
    log_stream_prefix = "collector"

    environment = {
      AOT_CONFIG_CONTENT = file("${path.module}/collector.yaml")
      AWS_REGION         = var.region
    }
  }

  # The endpoints stay unset: the services' defaults are localhost:4318, which in awsvpc is the
  # sidecar. Sampling is every request (X-Ray's free tier is 100,000 traces a month).
  telemetry_environment = {
    TRACING_EXPORT_ENABLED = "true"
    OTEL_METRICS_ENABLED   = "true"
    TRACING_PROBABILITY    = "1.0"
    # Micrometer's OTLP registry sends cumulative counts and awsemf passes them through, so a
    # rate would plot a running total.
    MANAGEMENT_OTLP_METRICS_EXPORT_AGGREGATIONTEMPORALITY = "delta"
  }
}
