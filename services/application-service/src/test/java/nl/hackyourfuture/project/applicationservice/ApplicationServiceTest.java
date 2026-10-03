package nl.hackyourfuture.project.applicationservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The base for tests that need application-service's Spring context. The context also needs
 * apps_db, from PostgresContainer, where it migrates as applications_user. The internal URLs,
 * the service signing key and job-service's key set URL are required to start. identity's URL is
 * the stub that serves the user key set; job-service's, which nothing calls yet, points at a
 * closed port. The trusted callers' key sets come from TestCallers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class ApplicationServiceTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresContainer::appsJdbcUrl);
        registry.add("spring.datasource.username", PostgresContainer::role);
        registry.add("spring.datasource.password", PostgresContainer::rolePassword);
        registry.add("app.internal.identity-url", () -> TestIdentity.instance().url());
        registry.add("app.internal.jobs-url", () -> "http://127.0.0.1:1");
        registry.add("app.service-jwt.private-key-file", () -> TestKey.path().toString());
        registry.add("app.internal.job-service-key-set-url", () -> TestCallers.instance().jwksUrl(TestCallers.JOB_SERVICE));
        registry.add("app.internal.trusted-issuers[0].name", () -> TestCallers.CALLER);
        registry.add("app.internal.trusted-issuers[0].key-set-url", () -> TestCallers.instance().jwksUrl(TestCallers.CALLER));
    }
}
