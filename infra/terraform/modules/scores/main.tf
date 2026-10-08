resource "aws_dynamodb_table" "scores" {
  name         = var.table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "skills_hash"
  range_key    = "posting_scorer"

  attribute {
    name = "skills_hash"
    type = "S"
  }

  attribute {
    name = "posting_scorer"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  tags = {
    Name = var.table_name
  }
}
