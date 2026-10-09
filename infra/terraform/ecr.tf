# One repository per image. force_delete lets terraform destroy empty it.
resource "aws_ecr_repository" "image" {
  for_each = toset(concat(keys(local.services), ["db-setup"]))

  name                 = "jobmatch/${each.key}"
  force_delete         = true
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}
