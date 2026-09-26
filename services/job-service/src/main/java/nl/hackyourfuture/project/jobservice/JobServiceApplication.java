package nl.hackyourfuture.project.jobservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Job search as its own service (Day 17). It scans the backend's packages too, because
 * {@code jobs} and its copies of {@code shared} keep their packages when they move here
 * (Track D), so they become beans unedited.
 */
@SpringBootApplication(scanBasePackages = {"nl.hackyourfuture.project.jobservice", "nl.hackyourfuture.project.backend"})
public class JobServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobServiceApplication.class, args);
    }
}
