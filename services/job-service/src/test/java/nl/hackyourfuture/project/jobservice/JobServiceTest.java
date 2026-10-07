package nl.hackyourfuture.project.jobservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The base for tests that need job-service's Spring context. Sets the service signing key
 * from a test key, and sets the trusted issuers from test servers. Disables the database
 * health check so /actuator/health stays UP with no Postgres. Mocks the JdbcClient so no
 * test ever connects to a database: any jobs route that calls it fails fast with 500.
 *
 * <p>Without the signing key, a context does not start.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class JobServiceTest {

    @MockitoBean(name = "jobsJdbcClient")
    JdbcClient jobsJdbcClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("management.health.db.enabled", () -> false);
        registry.add("app.service-jwt.private-key-file", () -> TestKey.path().toString());
        registry.add("app.internal.trusted-issuers[0].name", () -> TestCallers.CALLER);
        registry.add("app.internal.trusted-issuers[0].key-set-url", () -> TestCallers.instance().jwksUrl(TestCallers.CALLER));
    }
}
