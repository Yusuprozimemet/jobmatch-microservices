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

    // Day 11 moved every table out of app into its module's schema and handed it to the module's
    // role. Until then this asserted all seven were in app; it changed with the layout it pins.
    // Day 12 added refresh_tokens, identity's first table of its own, decided before the work.
    @Test
    void flywayHasMovedEachModulesTablesIntoItsOwnSchema() {
        assertThat(tablesIn("identity")).containsExactly(
                "password_reset_tokens identity_user",
                "refresh_tokens identity_user",
                "user_credentials identity_user",
                "user_profiles identity_user",
                "users identity_user");
        assertThat(tablesIn("applications")).containsExactly("saved_jobs applications_user");
        assertThat(tablesIn("matching")).containsExactly("job_match_scores matching_user");
    }

    // Only Flyway's history of V1-V14 stays behind.
    @Test
    void theAppSchemaHoldsNothingButFlywaysHistory() {
        assertThat(jdbc()
                .sql("SELECT tablename FROM pg_tables WHERE schemaname = 'app'")
                .query(String.class)
                .list())
                .containsExactly("flyway_schema_history");
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

    // Each table with its owner, leaving out any Flyway history a module keeps for itself.
    private List<String> tablesIn(String schema) {
        return jdbc()
                .sql("""
                        SELECT tablename || ' ' || tableowner FROM pg_tables
                        WHERE schemaname = :schema AND tablename <> 'flyway_schema_history'
                        ORDER BY tablename
                        """)
                .param("schema", schema)
                .query(String.class)
                .list();
    }
}
