# The ADOT collector each JVM service runs beside it (Day 34). infra-ci starts this image with
# collector.yaml, so a pin the configuration does not start on fails before any plan.
locals {
  collector_image = "public.ecr.aws/aws-observability/aws-otel-collector:v0.50.0"
}
