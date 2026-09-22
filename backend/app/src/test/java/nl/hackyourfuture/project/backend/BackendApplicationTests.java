package nl.hackyourfuture.project.backend;

import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application boots and its schema is migrated.
 *
 * <p>Was a bare context-load test with its own Testcontainers configuration. It now runs on
 * the shared harness, because a second configuration means a second container.
 */
class BackendApplicationTests extends IntegrationTest {

    @Test
    void flywayHasMigratedTheAppSchema() {
        List<String> tables = jdbc()
                .sql("SELECT tablename FROM pg_tables WHERE schemaname = 'app' ORDER BY tablename")
                .query(String.class)
                .list();

        assertThat(tables).contains(
                "flyway_schema_history",
                "job_match_scores",
                "password_reset_tokens",
                "saved_jobs",
                "user_credentials",
                "user_profiles",
                "users");
    }

    @Test
    void martTablesAreOutsideTheAppSchema() {
        // The split has to keep working because these live in their own schema, not because
        // nobody noticed they were in `app`.
        assertThat(jdbc()
                .sql("SELECT tablename FROM pg_tables WHERE schemaname = 'analytics' ORDER BY tablename")
                .query(String.class)
                .list())
                .containsExactly("fct_postings", "fct_postings_cities", "fct_postings_skills");
    }
}
