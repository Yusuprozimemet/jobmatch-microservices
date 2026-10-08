output "scores_table" {
  value = module.scores.table_name
}

output "bus_topic_arn" {
  value = module.bus.topic_arn
}

output "bus_queue_urls" {
  value = module.bus.queue_urls
}
