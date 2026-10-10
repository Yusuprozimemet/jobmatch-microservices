# The ALB's alarms (Day 34), beside Day 32's DLQ alarms in modules/bus. All three send to the bus
# module's alarm topic, so one subscription hears both.

resource "aws_cloudwatch_metric_alarm" "target_5xx" {
  alarm_name          = "jobmatch-alb-target-5xx"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [module.bus.alarm_topic_arn]

  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
  }
}

resource "aws_cloudwatch_metric_alarm" "elb_5xx" {
  alarm_name          = "jobmatch-alb-elb-5xx"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_ELB_5XX_Count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [module.bus.alarm_topic_arn]

  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
  }
}

# ECS deregisters a stopped task's target, so UnHealthyHostCount would stay 0; no data means no healthy host.
resource "aws_cloudwatch_metric_alarm" "frontend_healthy_hosts" {
  alarm_name          = "jobmatch-frontend-healthy-hosts"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [module.bus.alarm_topic_arn]

  dimensions = {
    TargetGroup  = aws_lb_target_group.frontend.arn_suffix
    LoadBalancer = aws_lb.main.arn_suffix
  }
}
