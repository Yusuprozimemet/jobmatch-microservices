package nl.hackyourfuture.project.backend.events;

import nl.hackyourfuture.project.backend.support.EventBus;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The relay publishes a deleted account's outbox row to the event bus (Day 26): one message per
 * queue, the v1 body of docs/events/user-deleted.md, and the row gone once SNS took it.
 *
 * <p>The only context with the relay on. It is closed after this class, or its scheduler would
 * go on taking other classes' outbox rows.
 *
 * <p>The queues are shared with the rest of the run, so a test takes only the messages for its
 * own {@code userId}. A receive raises the receive count of every message it sees: each receive
 * hides nothing ({@code visibilityTimeout 0}), and no test reads a dead-letter queue.
 */
@TestPropertySource(properties = "app.events.relay.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserDeletedRelayIT extends IntegrationTest {

    private static final JsonMapper JSON = new JsonMapper();
    private static final long WAIT_MILLIS = 5_000;

    @DynamicPropertySource
    static void useTheEventBus(DynamicPropertyRegistry registry) {
        registry.add("app.events.sns.endpoint", () -> EventBus.endpoint().toString());
        registry.add("app.events.user-deleted-topic-arn", EventBus::topicArn);
        registry.add("app.events.sns.access-key", () -> "dummy");
        registry.add("app.events.sns.secret-key", () -> "dummy");
    }

    @Test
    void aDeletedAccountArrivesOnBothQueuesAndLeavesTheOutbox() throws InterruptedException {
        TestUser user = aUser().create();

        assertThat(authenticatedAs(user).delete("/api/users/me").status()).isEqualTo(204);

        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        Map<String, Object> applications = receiveFor(user.id(), EventBus.APPLICATIONS_QUEUE, deadline);
        Map<String, Object> matching = receiveFor(user.id(), EventBus.MATCHING_QUEUE, deadline);
        assertThat(matching.get("eventId")).isEqualTo(applications.get("eventId"));
        // The row goes in the publish's transaction, which commits just after SNS answered.
        while (outboxRowsOf(user.id()) > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(outboxRowsOf(user.id())).isZero();
    }

    @Test
    void theMessageIsVersionOneOfTheEventPage() throws IOException {
        TestUser user = aUser().create();

        assertThat(authenticatedAs(user).delete("/api/users/me").status()).isEqualTo(204);

        Map<String, Object> body = receiveFor(user.id(), EventBus.APPLICATIONS_QUEUE,
                System.currentTimeMillis() + WAIT_MILLIS);
        receiveFor(user.id(), EventBus.MATCHING_QUEUE, System.currentTimeMillis() + WAIT_MILLIS);
        assertThat(body).containsOnlyKeys("eventId", "type", "version", "userId", "occurredAt");
        assertThat(body).containsEntry("type", "user.deleted").containsEntry("version", 1);
        UUID.fromString((String) body.get("eventId"));
        Instant.parse((String) body.get("occurredAt"));

        // Surefire runs in backend/app.
        String page = Files.readString(Path.of("../../docs/events/user-deleted.md"));
        assertThat(page).contains("`eventId`", "`type`", "`version`", "`userId`", "`occurredAt`",
                "Idempotent", "A repeat is a success", "An unreadable body goes to the dead-letter queue");
    }

    // The body of the first message for this user, deleted from the queue; other messages are
    // left as they were, visible to the test they belong to.
    private Map<String, Object> receiveFor(UUID userId, String queue, long deadline) {
        String url = EventBus.queueUrl(queue);
        while (System.currentTimeMillis() < deadline) {
            for (Message message : EventBus.sqs().receiveMessage(r -> r
                    .queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(1).visibilityTimeout(0)).messages()) {
                Map<String, Object> body = JSON.readValue(message.body(), new TypeReference<>() { });
                if (userId.toString().equals(body.get("userId"))) {
                    EventBus.sqs().deleteMessage(r -> r.queueUrl(url).receiptHandle(message.receiptHandle()));
                    return body;
                }
            }
        }
        return fail("no message for user " + userId + " on " + queue + " within " + WAIT_MILLIS + " ms");
    }

    private long outboxRowsOf(UUID userId) {
        return jdbc().sql("SELECT count(*) FROM identity.outbox WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }
}
