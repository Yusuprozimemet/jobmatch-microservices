package nl.hackyourfuture.project.backend;

import nl.hackyourfuture.project.backend.config.MigrateOnly;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {
    public static void main(String[] args) {
        if (MigrateOnly.requested(System.getenv("MIGRATE_ONLY"))) {
            System.exit(MigrateOnly.run(args));
        }
        SpringApplication.run(BackendApplication.class, args);
    }
}
