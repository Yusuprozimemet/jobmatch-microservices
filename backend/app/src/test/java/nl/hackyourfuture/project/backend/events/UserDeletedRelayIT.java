package nl.hackyourfuture.project.backend.events;

import nl.hackyourfuture.project.backend.support.EventBus;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The relay publishes a deleted account's outbox row to the event bus (Day 26): one message per
 * queue, the v1 body of docs/events/user-deleted.md, and the row gone once SNS took it.
 *
 * <p>The relay is on in every harness context since Day 25, and a row may be published by any of
 * them. The queues are shared with the rest of the run, so a test takes only the messages for its
 * own {@code userId}. A receive raises the receive count of every message it sees: each receive
 * hides nothing ({@code visibilityTimeout 0}), and no test reads a dead-letter queue.
 *
 * <p>And when it fails: with the bus down the delete still answers and the row waits for the bus;
 * a pass that publishes and then fails to delete the row publishes it again on the next pass,
 * with the same {@code eventId}, a duplicate by design.
 */
class UserDeletedRelayIT extends IntegrationTest {

    private static final JsonMapper JSON = new JsonMapper();
    private static final long WAIT_MILLIS = 5_000;

    @Autowired @Qualifier("identityDataSource") private DataSource identity;

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

    @Test
    void aDeleteWhileTheBusIsDownIsPublishedOnceItIsBack() throws InterruptedException {
        TestUser user = aUser().create();

        EventBus.pause();
        try {
            assertThat(authenticatedAs(user).delete("/api/users/me").status()).isEqualTo(204);

            // Longer than one relay period (1 s) plus the 2 s publish timeout, so at least one
            // pass has tried and failed.
            Thread.sleep(3_000);
            assertThat(outboxRowsOf(user.id())).isEqualTo(1);
        } finally {
            EventBus.unpause();
        }

        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        receiveFor(user.id(), EventBus.APPLICATIONS_QUEUE, deadline);
        receiveFor(user.id(), EventBus.MATCHING_QUEUE, deadline);
        while (outboxRowsOf(user.id()) > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(outboxRowsOf(user.id())).isZero();
    }

    @Test
    void aPublishThatFailsBeforeTheDeleteIsPublishedAgainWithTheSameEventId() throws InterruptedException {
        TestUser user = aUser().create();

        // A sequence, because nextval is not rolled back with the relay's transaction: the row's
        // delete fails exactly once, after its first publish. Only this user's row is refused.
        JdbcClient owner = JdbcClient.create(identity);
        owner.sql("CREATE SEQUENCE relay_fail_once").update();
        owner.sql("""
                CREATE FUNCTION fail_outbox_delete_once() RETURNS trigger AS $$
                BEGIN
                    IF nextval('relay_fail_once') = 1 THEN
                        RAISE EXCEPTION 'outbox delete refused once by the test';
                    END IF;
                    RETURN OLD;
                END;
                $$ LANGUAGE plpgsql
                """).update();
        try {
            owner.sql("""
                    CREATE TRIGGER fail_outbox_delete_once BEFORE DELETE ON outbox FOR EACH ROW
                    WHEN (OLD.user_id = '%s'::uuid) EXECUTE FUNCTION fail_outbox_delete_once()
                    """.formatted(user.id())).update();

            assertThat(authenticatedAs(user).delete("/api/users/me").status()).isEqualTo(204);

            long deadline = System.currentTimeMillis() + WAIT_MILLIS;
            List<Map<String, Object>> applicationsMessages = receiveFor(user.id(), EventBus.APPLICATIONS_QUEUE, 2, deadline);
            List<Map<String, Object>> matchingMessages = receiveFor(user.id(), EventBus.MATCHING_QUEUE, 2, deadline);

            assertThat(applicationsMessages).hasSize(2);
            assertThat(applicationsMessages.get(0).get("eventId"))
                    .as("duplicate by design: same eventId in the two applications messages")
                    .isEqualTo(applicationsMessages.get(1).get("eventId"));

            assertThat(matchingMessages).hasSize(2);
            assertThat(matchingMessages.get(0).get("eventId"))
                    .as("duplicate by design: same eventId in the two matching messages")
                    .isEqualTo(matchingMessages.get(1).get("eventId"));

            assertThat(applicationsMessages.get(0).get("eventId"))
                    .as("duplicate by design: same eventId across queues")
                    .isEqualTo(matchingMessages.get(0).get("eventId"));

            deadline = System.currentTimeMillis() + WAIT_MILLIS;
            while (outboxRowsOf(user.id()) > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertThat(outboxRowsOf(user.id())).isZero();
        } finally {
            owner.sql("DROP FUNCTION fail_outbox_delete_once() CASCADE").update();
            owner.sql("DROP SEQUENCE relay_fail_once").update();
        }
    }

    // The body of the first message for this user, deleted from the queue; other messages are
    // left as they were, visible to the test they belong to.
    private Map<String, Object> receiveFor(UUID userId, String queue, long deadline) {
        List<Map<String, Object>> messages = receiveFor(userId, queue, 1, deadline);
        if (messages.isEmpty()) {
            return fail("no message for user " + userId + " on " + queue + " within " + WAIT_MILLIS + " ms");
        }
        return messages.get(0);
    }

    // Up to count bodies for this user, in the order received, each deleted from the queue;
    // other messages are left as they were.
    private List<Map<String, Object>> receiveFor(UUID userId, String queue, int count, long deadline) {
        List<Map<String, Object>> received = new ArrayList<>();
        String url = EventBus.queueUrl(queue);
        while (received.size() < count && System.currentTimeMillis() < deadline) {
            for (Message message : EventBus.sqs().receiveMessage(r -> r
                    .queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(1).visibilityTimeout(0)).messages()) {
                Map<String, Object> body = JSON.readValue(message.body(), new TypeReference<>() { });
                if (userId.toString().equals(body.get("userId"))) {
                    received.add(body);
                    EventBus.sqs().deleteMessage(r -> r.queueUrl(url).receiptHandle(message.receiptHandle()));
                    if (received.size() >= count) {
                        break;
                    }
                }
            }
        }
        return received;
    }

    private long outboxRowsOf(UUID userId) {
        return jdbc().sql("SELECT count(*) FROM identity.outbox WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }
}
