package nl.hackyourfuture.project.applicationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Applications as its own service (Day 25). It scans the backend's packages too, because
 * {@code applications} and its copies of {@code shared} keep their packages when they move here,
 * so they become beans unedited, as on job-service (Day 17).
 */
@SpringBootApplication(scanBasePackages = {"nl.hackyourfuture.project.applicationservice", "nl.hackyourfuture.project.backend"})
@EnableConfigurationProperties(InternalUrls.class)
public class ApplicationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApplicationServiceApplication.class, args);
    }
}
