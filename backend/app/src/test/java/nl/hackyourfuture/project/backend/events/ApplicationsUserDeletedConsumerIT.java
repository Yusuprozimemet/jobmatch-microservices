package nl.hackyourfuture.project.backend.events;

import nl.hackyourfuture.project.backend.support.EventBus;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user.deleted consumer removes that user's saved jobs from the applications module (Day 27):
 * one message per event, the v1 body of docs/events/user-deleted.md, and the rows gone once the
 * consumer handled it.
 *
 * <p>The user still exists in identity, so only the consumer can remove the rows; the foreign
 * key constraint still deletes them on a real account delete. This test bypasses the key: it
 * sends the relay's envelope for a user who still exists to its own queue, and expects that
 * user's saved jobs to go.
 *
 * <p>The only context with the consumer on. It is closed after this class, or its thread would
 * go on taking other tests' messages.
 */
@TestPropertySource(properties = "app.events.consumer.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationsUserDeletedConsumerIT extends IntegrationTest {

    private static final JsonMapper JSON = new JsonMapper();
    private static final long WAIT_MILLIS = 5_000;
    private static final String QUEUE_NAME = "applications-consumer-it-" + UUID.randomUUID();
    private static String queueUrl;

    @DynamicPropertySource
    static void useOwnQueue(DynamicPropertyRegistry registry) {
        queueUrl = EventBus.createOwnQueue(QUEUE_NAME);
        registry.add("app.events.sqs.endpoint", () -> EventBus.endpoint().toString());
        registry.add("app.events.sqs.access-key", () -> "dummy");
        registry.add("app.events.sqs.secret-key", () -> "dummy");
        registry.add("app.events.user-deleted-queue-url", () -> queueUrl);
    }

    @Test
    void theEventRemovesThatUsersSavedJobsAndNoOneElses() throws InterruptedException {
        TestUser user = aUser().create();
        TestUser stranger = aUser().create();

        authenticatedAs(user).post("/api/saved-jobs", Map.of("postingId", "seed-0001"));
        authenticatedAs(user).post("/api/saved-jobs", Map.of("postingId", "seed-0002"));
        authenticatedAs(stranger).post("/api/saved-jobs", Map.of("postingId", "seed-0001"));
        authenticatedAs(stranger).post("/api/saved-jobs", Map.of("postingId", "seed-0002"));

        assertThat(savedJobsOf(user.id())).isEqualTo(2);
        assertThat(savedJobsOf(stranger.id())).isEqualTo(2);

        sendEvent(user.id());

        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (savedJobsOf(user.id()) > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        assertThat(savedJobsOf(user.id())).isZero();
        assertThat(savedJobsOf(stranger.id())).isEqualTo(2);
    }

    private void sendEvent(UUID userId) {
        sendRaw(eventBody(userId));
    }

    private void sendRaw(String body) {
        EventBus.sqs().sendMessage(r -> r
                .queueUrl(queueUrl)
                .messageBody(body));
    }

    private String eventBody(UUID userId) {
        return envelope(UUID.randomUUID().toString(), "user.deleted", 1, userId.toString());
    }

    private static String envelope(String eventId, String type, Object version, String userId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("type", type);
        event.put("version", version);
        event.put("userId", userId);
        event.put("occurredAt", Instant.now().toString());
        return JSON.writeValueAsString(event);
    }

    private long savedJobsOf(UUID userId) {
        return jdbc().sql("SELECT count(*) FROM saved_jobs WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }
}
