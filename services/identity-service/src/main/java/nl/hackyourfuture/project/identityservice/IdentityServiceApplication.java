package nl.hackyourfuture.project.identityservice;

import nl.hackyourfuture.project.backend.config.MigrateOnly;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Identity as its own service, named like its siblings (Day 43). Its code keeps the monolith's
 * {@code backend} packages, as job-service's does, so it scans them too: the controllers and
 * repositories there become beans unedited.
 */
@SpringBootApplication(scanBasePackages = {"nl.hackyourfuture.project.identityservice", "nl.hackyourfuture.project.backend"})
public class IdentityServiceApplication {
    public static void main(String[] args) {
        if (MigrateOnly.requested(System.getenv("MIGRATE_ONLY"))) {
            System.exit(MigrateOnly.run(args));
        }
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
