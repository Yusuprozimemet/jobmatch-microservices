package nl.hackyourfuture.project.matchingservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Matching as its own service (Day 21). It scans the backend's packages too, because
 * {@code matching} and its copies of {@code shared} keep their packages when they move here,
 * so they become beans unedited, as on job-service (Day 17).
 */
@SpringBootApplication(scanBasePackages = {"nl.hackyourfuture.project.matchingservice", "nl.hackyourfuture.project.backend"})
@EnableConfigurationProperties(InternalUrls.class)
public class MatchingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchingServiceApplication.class, args);
    }
}
