package nl.hackyourfuture.project.backend.identity.outbox;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
}
