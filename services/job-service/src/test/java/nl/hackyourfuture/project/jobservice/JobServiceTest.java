package nl.hackyourfuture.project.jobservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The base for tests that need job-service's Spring context. Sets the service signing key
 * from a test key, so contexts do not need one from the environment, and sets the trusted
 * issuers from test servers.
 *
 * <p>Without the key set and the backend key set URL, a context does not start.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class JobServiceTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.service-jwt.private-key-file", () -> TestKey.path().toString());
        registry.add("app.internal.backend-key-set-url", () -> TestCallers.instance().jwksUrl(TestCallers.BACKEND));
        registry.add("app.internal.trusted-issuers[0].name", () -> TestCallers.CALLER);
        registry.add("app.internal.trusted-issuers[0].key-set-url", () -> TestCallers.instance().jwksUrl(TestCallers.CALLER));
    }
}
