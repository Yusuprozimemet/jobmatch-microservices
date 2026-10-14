package nl.hackyourfuture.project.backend.identity.outbox;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Events waiting for the relay, written in the transaction of the change they report.
 */
@Repository
public class Outbox {
    private final JdbcClient jdbcClient;

    public static final String USER_DELETED = "user.deleted";

    public Outbox(@Qualifier("identityJdbcClient") JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void add(String type, UUID userId) {
        jdbcClient
                .sql("INSERT INTO outbox (type, user_id) VALUES (:type, :userId)")
                .param("type", type)
                .param("userId", userId)
                .update();
    }

    /**
     * The ids of unpublished rows, ordered by when they were created, limited to
     * {@code limit} rows. For the relay to claim and publish one pass at a time.
     */
    public List<UUID> pendingIds(int limit) {
        return jdbcClient
                .sql("SELECT id FROM outbox ORDER BY occurred_at LIMIT :limit")
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /**
     * A row by id, with a SELECT FOR UPDATE SKIP LOCKED so only one relay holder publishes it.
     * Empty if another relay holds it or it is already gone.
     */
    public Optional<Row> claim(UUID id) {
        return jdbcClient
                .sql("SELECT id, type, user_id, occurred_at FROM outbox WHERE id = :id FOR UPDATE SKIP LOCKED")
                .param("id", id)
                .query((rs, rowNum) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * Delete a published row by id. Part of the relay transaction after a successful publish.
     */
    public void remove(UUID id) {
        jdbcClient
                .sql("DELETE FROM outbox WHERE id = :id")
                .param("id", id)
                .update();
    }

    /**
     * An outbox row: id, type, user_id, occurred_at. Sent to SNS as an event.
     */
    public record Row(UUID id, String type, UUID userId, Instant occurredAt) {
    }
}
