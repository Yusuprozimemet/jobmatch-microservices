provider "aws" {
  region = var.region

  default_tags {
    tags = {
      project = "jobmatch"
    }
  }
}

# CI's ci_override.tf adds skip_* flags.
