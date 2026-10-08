module "network" {
  source = "./modules/network"

  availability_zones = var.availability_zones
}

module "database" {
  source = "./modules/database"

  subnet_ids         = module.network.private_subnet_ids
  security_group_ids = [module.network.database_security_group_id]
}
