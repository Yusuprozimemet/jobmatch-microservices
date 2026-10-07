terraform {
  required_version = "~> 1.16.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.67"
    }
  }

  backend "s3" {
    bucket                      = "jobmatch-microservices-tfstate"
    key                         = "jobmatch/terraform.tfstate"
    region                      = "eu-west-1"
    encrypt                     = true
    use_lockfile                = true
    endpoints                   = { s3 = "http://localhost:4566" }
    use_path_style              = true
    skip_credentials_validation = true
    skip_requesting_account_id  = true
    skip_metadata_api_check     = true
  }
}
