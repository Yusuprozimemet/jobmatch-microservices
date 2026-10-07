provider "aws" {
  region            = var.region
  access_key        = "test"
  secret_key        = "test"
  s3_use_path_style = true

  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = true

  endpoints {
    s3         = var.endpoint
    sns        = var.endpoint
    sqs        = var.endpoint
    dynamodb   = var.endpoint
    cloudwatch = var.endpoint
    sts        = var.endpoint
    iam        = var.endpoint
  }
}
