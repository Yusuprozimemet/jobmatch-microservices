# The public entry point: HTTPS to the frontend, and HTTP redirected to HTTPS. With var.domain, the
# certificate is an ACM one validated by DNS records in var.route53_zone_id, and var.domain is an
# alias record to the ALB in the same zone. Without it (Day 35, F1), a self-signed certificate for
# the ALB's DNS name is imported to ACM instead: no client trusts it, and the destroy removes it.
resource "aws_lb" "main" {
  name               = "jobmatch"
  load_balancer_type = "application"
  internal           = false
  subnets            = module.network.public_subnet_ids
  security_groups    = [module.network.alb_security_group_id]
}

resource "aws_lb_target_group" "frontend" {
  port        = 3000
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = module.network.vpc_id

  health_check {
    path    = "/" # the frontend has no actuator
    matcher = "200"
  }
}

# Without a domain: a key and a self-signed certificate for the ALB's DNS name, imported below.
resource "tls_private_key" "main" {
  count     = var.domain == "" ? 1 : 0
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "tls_self_signed_cert" "main" {
  count           = var.domain == "" ? 1 : 0
  private_key_pem = tls_private_key.main[0].private_key_pem

  subject {
    common_name = aws_lb.main.dns_name
  }

  dns_names             = [aws_lb.main.dns_name]
  validity_period_hours = 720
  allowed_uses          = ["key_encipherment", "digital_signature", "server_auth"]
}

resource "aws_acm_certificate" "main" {
  domain_name       = var.domain != "" ? var.domain : null
  validation_method = var.domain != "" ? "DNS" : null
  private_key       = var.domain == "" ? tls_private_key.main[0].private_key_pem : null
  certificate_body  = var.domain == "" ? tls_self_signed_cert.main[0].cert_pem : null

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "certificate_validation" {
  for_each = var.domain == "" ? {} : {
    for dvo in aws_acm_certificate.main.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  allow_overwrite = true
  name            = each.value.name
  records         = [each.value.record]
  ttl             = 60
  type            = each.value.type
  zone_id         = var.route53_zone_id
}

resource "aws_acm_certificate_validation" "main" {
  count = var.domain != "" ? 1 : 0

  certificate_arn         = aws_acm_certificate.main.arn
  validation_record_fqdns = [for record in aws_route53_record.certificate_validation : record.fqdn]
}

# The domain itself: an alias to the ALB, so https://<domain> resolves.
resource "aws_route53_record" "domain" {
  count = var.domain != "" ? 1 : 0

  zone_id = var.route53_zone_id
  name    = var.domain
  type    = "A"

  alias {
    name                   = aws_lb.main.dns_name
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = true
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.domain != "" ? aws_acm_certificate_validation.main[0].certificate_arn : aws_acm_certificate.main.arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.frontend.arn
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}
