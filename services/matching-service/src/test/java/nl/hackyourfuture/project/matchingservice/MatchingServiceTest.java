package nl.hackyourfuture.project.matchingservice;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The base for tests that need matching-service's Spring context. The internal URLs and the
 * service signing key are required to start; both internal URLs are the stub upstream, which a
 * test sets per path, and identity's user key set is pinned to {@code TestIdentity}, since it
 * would otherwise be looked up at the stub. The score store is {@link DynamoDbContainer}
 * (Day 22), and the model is {@code StubLlm}, with a key, since a blank one turns the model call
 * off. The user.deleted consumer is off (Day 27): only UserDeletedConsumerTest turns it on, on
 * queues of its own, in a context it closes after itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class MatchingServiceTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.llm.base-url", () -> StubLlm.instance().baseUrl());
        registry.add("app.llm.api-key", () -> "stub-key");
        registry.add("app.llm.model", () -> "stub-model");
        registry.add("app.llm.reasoning-effort", () -> "");
        registry.add("app.service-jwt.private-key-file", () -> TestKey.path().toString());
        registry.add("app.internal.identity-url", () -> StubUpstream.instance().baseUrl());
        registry.add("app.internal.jobs-url", () -> StubUpstream.instance().baseUrl());
        registry.add("app.identity.jwks-url", () -> TestIdentity.instance().url() + "/.well-known/jwks.json");
        // A subclass's own @DynamicPropertySource runs first, so it cannot replace these; it sets
        // test.scores.* instead, which these defer to.
        registry.add("app.scores.endpoint", () -> "${test.scores.endpoint:" + DynamoDbContainer.endpoint() + "}");
        registry.add("app.scores.create-table", () -> "${test.scores.create-table:true}");
        // Off: a live consumer in a cached context would keep polling after its test. A consumer test
        // sets test.events.consumer.enabled, which this defers to, as the scores lines do.
        registry.add("app.events.consumer.enabled", () -> "${test.events.consumer.enabled:false}");
    }

    protected StubLlm model() {
        return StubLlm.instance();
    }
}
