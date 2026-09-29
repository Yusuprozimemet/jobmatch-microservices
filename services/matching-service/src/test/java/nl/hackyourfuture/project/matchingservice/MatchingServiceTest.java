package nl.hackyourfuture.project.matchingservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The base for tests that need matching-service's Spring context. The internal URLs and the service
 * signing key are required to start; identity's URL is the stub that serves the user key set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class MatchingServiceTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.service-jwt.private-key-file", () -> TestKey.path().toString());
        registry.add("app.internal.identity-url", () -> TestIdentity.instance().url());
        registry.add("app.internal.jobs-url", () -> "http://127.0.0.1:1");
    }
}
