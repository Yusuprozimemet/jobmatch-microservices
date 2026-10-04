package nl.hackyourfuture.project.applicationservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user.deleted consumer removes that user's saved jobs from apps_db (Day 27): one message
 * per event, the v1 body of docs/events/user-deleted.md, and the rows gone once the consumer
 * handled it. Idempotent: the same event three times leaves the same state as once.
 *
 * <p>The test sends the relay's envelope for a user id to the consumer's own queue and expects
 * only that user's rows in apps_db to go.
 *
 * <p>The only context with the consumer on. It is closed after this class, or its thread would
 * go on taking other tests' messages.
 */
@TestPropertySource(properties = "app.events.consumer.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserDeletedConsumerTest extends ApplicationServiceTest {

    private static final JsonMapper JSON = new JsonMapper();
    private static final long WAIT_MILLIS = 5_000;
    private static final String QUEUE_NAME = "applications-consumer-test-" + UUID.randomUUID();
    private static String queueUrl;
    private static String dlqUrl;

    @DynamicPropertySource
    static void useOwnQueue(DynamicPropertyRegistry registry) {
        queueUrl = SqsContainer.createOwnQueue(QUEUE_NAME);
        dlqUrl = SqsContainer.queueUrlOf(SqsContainer.deadLetterQueue(QUEUE_NAME));
        registry.add("app.events.sqs.endpoint", SqsContainer::endpoint);
        registry.add("app.events.sqs.access-key", () -> "dummy");
        registry.add("app.events.sqs.secret-key", () -> "dummy");
        registry.add("app.events.user-deleted-queue-url", () -> queueUrl);
    }

    @BeforeEach
    void clean() throws SQLException {
        try (Connection admin = PostgresContainer.adminConnection();
             PreparedStatement stmt = admin.prepareStatement("DELETE FROM applications.saved_jobs")) {
            stmt.execute();
        }
    }

    @Test
    void theEventRemovesThatUsersSavedJobsAndNoOneElses() throws InterruptedException, SQLException {
        UUID user = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();

        insert(user, "seed-0001");
        insert(user, "seed-0002");
        insert(stranger, "seed-0001");
        insert(stranger, "seed-0002");

        assertThat(savedJobsOf(user)).isEqualTo(2);
        assertThat(savedJobsOf(stranger)).isEqualTo(2);

        sendEvent(user);

        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (savedJobsOf(user) > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        assertThat(savedJobsOf(user)).isZero();
        assertThat(savedJobsOf(stranger)).isEqualTo(2);
    }

    @Test
    void theSameEventThreeTimesIsDrainedWithNothingDeadLettered() throws InterruptedException, SQLException {
        UUID user = UUID.randomUUID();

        insert(user, "seed-0001");
        insert(user, "seed-0002");

        String body = eventBody(user);
        long sent = System.currentTimeMillis();
        sendRaw(body);
        sendRaw(body);
        sendRaw(body);

        long deadline = sent + WAIT_MILLIS;
        while (SqsContainer.approximateCount(queueUrl) > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(SqsContainer.approximateCount(queueUrl)).isZero();

        assertThat(savedJobsOf(user)).isZero();

        // Check the DLQ at 10 s after the first send.
        Thread.sleep(Math.max(0, sent + 10_000 - System.currentTimeMillis()));
        assertThat(SqsContainer.approximateCount(dlqUrl)).isZero();
    }

    @Test
    void unreadableBodiesAreDeadLetteredAndTheQueueGoesOn() throws InterruptedException, SQLException {
        UUID user = UUID.randomUUID();

        insert(user, "seed-0001");
        insert(user, "seed-0002");

        String marker1 = UUID.randomUUID().toString();
        String marker2 = UUID.randomUUID().toString();
        String marker3 = UUID.randomUUID().toString();

        long sent = System.currentTimeMillis();
        sendRaw("not json " + marker1);
        sendRaw(invalidTypeBody(marker2));
        sendRaw(invalidVersionBody(marker3));
        sendRaw(eventBody(user));

        // Wait for the valid event to be handled.
        long deadline = sent + WAIT_MILLIS;
        while (savedJobsOf(user) > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(savedJobsOf(user)).isZero();

        // Receive all three bad messages from the DLQ, anchored to the first send.
        Set<String> found = new HashSet<>();
        deadline = sent + 15_000;
        while (found.size() < 3 && System.currentTimeMillis() < deadline) {
            var messages = SqsContainer.sqs().receiveMessage(r -> r
                    .queueUrl(dlqUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(1))
                    .messages();
            for (var msg : messages) {
                String body = msg.body();
                if (body.contains(marker1)) {
                    found.add(marker1);
                } else if (body.contains(marker2)) {
                    found.add(marker2);
                } else if (body.contains(marker3)) {
                    found.add(marker3);
                }
                SqsContainer.sqs().deleteMessage(r -> r
                        .queueUrl(dlqUrl)
                        .receiptHandle(msg.receiptHandle()));
            }
        }
        assertThat(found).as("all three markers found in DLQ").containsExactlyInAnyOrder(marker1, marker2, marker3);
    }

    private void insert(UUID userId, String postingId) throws SQLException {
        try (Connection admin = PostgresContainer.adminConnection();
             PreparedStatement stmt = admin.prepareStatement(
                     "INSERT INTO applications.saved_jobs (user_id, posting_id) VALUES (?, ?)")) {
            stmt.setObject(1, userId);
            stmt.setString(2, postingId);
            stmt.execute();
        }
    }

    private void sendEvent(UUID userId) {
        sendRaw(eventBody(userId));
    }

    private void sendRaw(String body) {
        SqsContainer.sqs().sendMessage(r -> r
                .queueUrl(queueUrl)
                .messageBody(body));
    }

    private String eventBody(UUID userId) {
        return envelope(UUID.randomUUID().toString(), "user.deleted", 1, userId.toString());
    }

    private String invalidTypeBody(String marker) {
        return envelope(marker, "user.created", 1, UUID.randomUUID().toString());
    }

    private String invalidVersionBody(String marker) {
        return envelope(marker, "user.deleted", 2, UUID.randomUUID().toString());
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

    private long savedJobsOf(UUID userId) throws SQLException {
        try (Connection admin = PostgresContainer.adminConnection();
             PreparedStatement stmt = admin.prepareStatement(
                     "SELECT count(*) FROM applications.saved_jobs WHERE user_id = ?")) {
            stmt.setObject(1, userId);
            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }
}
