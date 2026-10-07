output "endpoint" {
  value       = var.endpoint
  description = "Gives the first apply a change to prompt on, so CI's lock test has an apply waiting at its prompt before Track C adds resources."
}
