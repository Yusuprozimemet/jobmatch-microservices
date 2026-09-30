package nl.hackyourfuture.project.matchingservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The base for tests that need matching-service's Spring context. The internal URLs and the
 * service signing key are required to start; both internal URLs are the stub upstream, which a
 * test sets per path, and identity's user key set is pinned to {@code TestIdentity}, since it
 * would otherwise be looked up at the stub. The database is the test Postgres, and the model
 * {@code StubLlm}, with a key, since a blank one turns the model call off.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class MatchingServiceTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.datasource.matching.url", PostgresContainer::jdbcUrl);
        registry.add("app.datasource.matching.username", PostgresContainer::username);
        registry.add("app.datasource.matching.password", PostgresContainer::password);
        registry.add("app.llm.base-url", () -> StubLlm.instance().baseUrl());
        registry.add("app.llm.api-key", () -> "stub-key");
        registry.add("app.llm.model", () -> "stub-model");
        registry.add("app.llm.reasoning-effort", () -> "");
        registry.add("app.service-jwt.private-key-file", () -> TestKey.path().toString());
        registry.add("app.internal.identity-url", () -> StubUpstream.instance().baseUrl());
        registry.add("app.internal.jobs-url", () -> StubUpstream.instance().baseUrl());
        registry.add("app.identity.jwks-url", () -> TestIdentity.instance().url() + "/.well-known/jwks.json");
    }

    protected StubLlm model() {
        return StubLlm.instance();
    }
}
