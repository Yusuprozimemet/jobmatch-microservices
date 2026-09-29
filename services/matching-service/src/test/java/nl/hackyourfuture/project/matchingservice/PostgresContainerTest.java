package nl.hackyourfuture.project.matchingservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 21 Track B3a: the test Postgres holds what the service's {@code db/matching} migrations start
 * from in production. Every statement is unqualified and runs as matching_user, so each test also
 * proves the search path the service will connect with.
 */
class PostgresContainerTest {

    private static final String HASH = "a".repeat(64);

    @BeforeEach
    void emptyTheCache() throws SQLException {
        run("DELETE FROM job_match_scores");
    }

    @Test
    void matchingUserWritesAndReadsTheScoreCache() throws SQLException {
        run("INSERT INTO job_match_scores (skills_hash, posting_id, scorer_version, score, reason)"
                + " VALUES ('" + HASH + "', 'p1', 'v1', 80, 'java')");

        try (Connection connection = PostgresContainer.connect();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT score, reason, scored_at FROM job_match_scores WHERE posting_id = 'p1'")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getInt("score")).isEqualTo(80);
            assertThat(row.getString("reason")).isEqualTo("java");
            assertThat(row.getTimestamp("scored_at")).isNotNull();
        }
    }

    @Test
    void aScoreOutsideZeroToHundredIsRefused() {
        assertThatThrownBy(() -> run("INSERT INTO job_match_scores (skills_hash, posting_id, scorer_version, score)"
                + " VALUES ('" + HASH + "', 'p2', 'v1', 101)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("job_match_scores_score_range");
    }

    // db/matching's V1 revokes what others were granted, and only an owner can.
    @Test
    void matchingUserOwnsTheSchemaAndTheTable() throws SQLException {
        try (Connection connection = PostgresContainer.connect();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT n.nspowner::regrole::text, c.relowner::regrole::text"
                     + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                     + " WHERE n.nspname = 'matching' AND c.relname = 'job_match_scores'")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo("matching_user");
            assertThat(row.getString(2)).isEqualTo("matching_user");
        }
    }

    private static void run(String sql) throws SQLException {
        try (Connection connection = PostgresContainer.connect();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
