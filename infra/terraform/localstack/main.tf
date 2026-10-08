module "scores" {
  source = "../modules/scores"
}

module "bus" {
  source = "../modules/bus"

  alert_email = var.alert_email
}
