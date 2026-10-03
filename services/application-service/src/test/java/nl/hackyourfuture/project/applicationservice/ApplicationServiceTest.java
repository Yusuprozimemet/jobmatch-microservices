package nl.hackyourfuture.project.applicationservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The base for tests that need application-service's Spring context. The context also needs
 * apps_db, from PostgresContainer, where it migrates as applications_user. The internal URLs
 * are required to start; nothing calls them yet, so they point at a closed port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class ApplicationServiceTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresContainer::appsJdbcUrl);
        registry.add("spring.datasource.username", PostgresContainer::role);
        registry.add("spring.datasource.password", PostgresContainer::rolePassword);
        registry.add("app.internal.identity-url", () -> "http://127.0.0.1:1");
        registry.add("app.internal.jobs-url", () -> "http://127.0.0.1:1");
    }
}
