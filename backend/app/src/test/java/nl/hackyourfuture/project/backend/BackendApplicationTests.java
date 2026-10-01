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
    // Day 12 added refresh_tokens, identity's first table of its own, decided before the work;
    // Day 14 added pending_google_links, the Google identities the session used to hold.
    // Day 23 dropped job_match_scores (V15); matching keeps its schema and role, now empty.
    @Test
    void flywayHasMovedEachModulesTablesIntoItsOwnSchema() {
        assertThat(tablesIn("identity")).containsExactly(
                "password_reset_tokens identity_user",
                "pending_google_links identity_user",
                "refresh_tokens identity_user",
                "user_credentials identity_user",
                "user_profiles identity_user",
                "users identity_user");
        assertThat(tablesIn("applications")).containsExactly("saved_jobs applications_user");
        assertThat(tablesIn("matching")).isEmpty();
    }

    // Only Flyway's history of V1-V15 stays behind.
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
        // The split has to keep working because the mart lives in jobs_db, not in project_db,
        // and neither database has the tables in `app`.
        assertThat(jobsJdbc()
                .sql("SELECT tablename FROM pg_tables WHERE schemaname = 'analytics' ORDER BY tablename")
                .query(String.class)
                .list())
                .containsExactly("fct_postings", "fct_postings_cities", "fct_postings_skills");
        assertThat(jdbc()
                .sql("SELECT count(*) FROM pg_namespace WHERE nspname = 'analytics'")
                .query(Long.class)
                .single())
                .isZero();
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
