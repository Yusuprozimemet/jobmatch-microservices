package nl.hackyourfuture.project.backend.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts the database back to its starting state between tests: an empty {@code app} schema and
 * the baseline mart.
 *
 * <p>Truncate rather than roll back a transaction. The tests drive the application over HTTP,
 * so the writes happen on the server's own connections and there is no test-side transaction
 * to roll back.
 */
public final class TestDatabase {

    private static final List<String> MART_TABLES = List.of(
            "analytics.fct_postings",
            "analytics.fct_postings_cities",
            "analytics.fct_postings_skills");

    // Flyway's own bookkeeping. Truncating it would make the next context start re-run every
    // migration against a schema that already has the objects.
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    private static final JdbcClient JDBC = JdbcClient.create(PostgresContainer.dataSource());
    private static final String SEED = SqlScripts.read("fixtures/analytics-seed.sql");

    private TestDatabase() {
    }

    /** A JdbcClient on the test container, for fixtures and for asserting on stored rows. */
    public static JdbcClient jdbc() {
        return JDBC;
    }

    public static void reset() {
        List<String> tables = new ArrayList<>(applicationTables());
        tables.addAll(MART_TABLES);
        if (!tables.isEmpty()) {
            // One statement: TRUNCATE takes a table list, and CASCADE follows the foreign keys
            // so the order the tables come back in does not matter.
            JDBC.sql("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE").update();
        }
        PostgresContainer.execute(SEED);
    }

    /**
     * Read from the catalogue instead of a hardcoded list, so a table added by a future
     * migration is cleaned up without anyone remembering to edit this class.
     */
    private static List<String> applicationTables() {
        return JDBC.sql("""
                        SELECT quote_ident(schemaname) || '.' || quote_ident(tablename)
                        FROM pg_tables
                        WHERE schemaname = 'app' AND tablename <> :flywayHistory
                        """)
                .param("flywayHistory", FLYWAY_HISTORY)
                .query(String.class)
                .list();
    }
}
