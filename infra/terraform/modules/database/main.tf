resource "aws_db_subnet_group" "main" {
  name       = "${var.name}-db-subnet-group"
  subnet_ids = var.subnet_ids

  tags = {
    Name = var.name
  }
}

resource "aws_db_instance" "main" {
  identifier                  = var.name
  engine                      = "postgres"
  engine_version              = var.engine_version
  instance_class              = var.instance_class
  allocated_storage           = 20
  storage_encrypted           = true
  db_subnet_group_name        = aws_db_subnet_group.main.name
  vpc_security_group_ids      = var.security_group_ids
  publicly_accessible         = false
  manage_master_user_password = true
  username                    = "jobmatch_admin"
  multi_az                    = false
  deletion_protection         = false
  skip_final_snapshot         = true

  tags = {
    Name = var.name
  }
}
