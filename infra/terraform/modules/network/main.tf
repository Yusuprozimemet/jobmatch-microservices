resource "aws_vpc" "main" {
  cidr_block           = var.cidr_block
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = var.name
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = var.name
  }
}

resource "aws_subnet" "public" {
  count                   = length(var.availability_zones)
  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.cidr_block, 2, count.index)
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = true

  tags = {
    Name = "${var.name}-public-${count.index + 1}"
  }
}

resource "aws_subnet" "private" {
  count             = length(var.availability_zones)
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.cidr_block, 2, count.index + 2)
  availability_zone = var.availability_zones[count.index]

  tags = {
    Name = "${var.name}-private-${count.index + 1}"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.name}-public"
  }
}

resource "aws_route" "public_igw" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.main.id
}

resource "aws_route_table_association" "public" {
  count          = length(aws_subnet.public)
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# No NAT gateway: about $35 a month before traffic. The ECS tasks run in the public subnets
# with a public IP; the private subnets hold only the database and need no way out.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.name}-private"
  }
}

resource "aws_route_table_association" "private" {
  count          = length(aws_subnet.private)
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

resource "aws_security_group" "tasks" {
  name        = "${var.name}-tasks"
  description = "Security group for ECS tasks"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.name}-tasks"
  }
}

resource "aws_vpc_security_group_egress_rule" "tasks" {
  security_group_id = aws_security_group.tasks.id

  description = "Allow all outbound traffic"
  ip_protocol = "-1"
  cidr_ipv4   = "0.0.0.0/0"

  tags = {
    Name = "${var.name}-tasks-egress-all"
  }
}

resource "aws_security_group" "database" {
  name        = "${var.name}-database"
  description = "Security group for RDS database"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.name}-database"
  }
}

resource "aws_vpc_security_group_ingress_rule" "database_from_tasks" {
  security_group_id = aws_security_group.database.id

  description                  = "PostgreSQL from tasks"
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  referenced_security_group_id = aws_security_group.tasks.id

  tags = {
    Name = "${var.name}-database-from-tasks"
  }
}
