package nl.hackyourfuture.project.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Turns on @Scheduled - off by default in Spring Boot. Only JobMatchScoreCleanup uses it.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
