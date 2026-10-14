package nl.hackyourfuture.project.backend.identity.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sns.SnsClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes the outbox to SNS (Day 26): every second, each unpublished row is published and then
 * deleted, in one transaction per row. At least once: a pass that publishes and fails before the
 * delete commits leaves the row, and the next pass publishes it again with the same event id.
 * The body is version 1 of docs/events/user-deleted.md.
 */
@Slf4j
public class OutboxRelay {
    private static final int ROWS_PER_PASS = 100;
    private static final int VERSION = 1;

    private final Outbox outbox;
    private final SnsClient sns;
    private final String topicArn;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper = new JsonMapper();

    public OutboxRelay(Outbox outbox, SnsClient sns, String topicArn, PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.sns = sns;
        this.topicArn = topicArn;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelay = 1000)
    public void relay() {
        for (UUID id : outbox.pendingIds(ROWS_PER_PASS)) {
            try {
                transaction.executeWithoutResult(status -> publish(id));
            } catch (RuntimeException e) {
                // The transaction rolled back, so the row stays for the next pass; the rest of
                // this pass goes on.
                log.warn("Outbox row {} not published: {}", id, e.getMessage());
            }
        }
    }

    // Empty claim: another relay holds the row, or it went since the ids were read.
    private void publish(UUID id) {
        outbox.claim(id).ifPresent(row -> {
            var event = new UserDeletedEvent(row.id(), row.type(), VERSION, row.userId(), row.occurredAt());
            sns.publish(r -> r.topicArn(topicArn).message(jsonMapper.writeValueAsString(event)));
            outbox.remove(row.id());
        });
    }

    private record UserDeletedEvent(UUID eventId, String type, int version, UUID userId, Instant occurredAt) {
    }
}
