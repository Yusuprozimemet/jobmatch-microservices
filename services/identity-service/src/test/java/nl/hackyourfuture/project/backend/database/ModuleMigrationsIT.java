package nl.hackyourfuture.project.backend.database;

import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each module keeps its own migration history, in its own schema, under its own role (Day 11).
 *
 * <p>app's history holds V1-V16, including the moves that brought each module's tables in. Each
 * module's own Flyway then baselines at 0, so what a module migrates from here, starting with
 * Day 12's {@code identity.refresh_tokens}, is recorded where only that module's login can write.
 */
class ModuleMigrationsIT extends IntegrationTest {

    // applications has none here since Day 25: application-service migrates its own database, apps_db.
    @ParameterizedTest
    @ValueSource(strings = {"identity"})
    void eachModuleHasItsOwnHistoryOwnedByItsRole(String module) {
        assertThat(jdbc()
                .sql("SELECT tableowner FROM pg_tables WHERE schemaname = :schema AND tablename = 'flyway_schema_history'")
                .param("schema", module)
                .query(String.class)
                .optional())
                .contains(module + "_user");
    }

    // identity's history gained its first migration on Day 12, as the spec decided before the work,
    // and its second on Day 14. Day 38's revokes are the third;
    // Day 26's outbox is the fourth.
    @ParameterizedTest
    @CsvSource({"identity, 0 BASELINE|1 SQL|2 SQL|3 SQL|4 SQL"})
    void andItStartsAtTheBaseline(String module, String history) {
        assertThat(jdbc()
                .sql("SELECT version || ' ' || type FROM " + module + ".flyway_schema_history ORDER BY installed_rank")
                .query(String.class)
                .list())
                .containsExactly(history.split("\\|"));
    }

    @Test
    void appKeepsTheHistoryOfEverythingBefore() {
        assertThat(jdbc()
                .sql("SELECT max(version::int) FROM app.flyway_schema_history WHERE success")
                .query(Integer.class)
                .single())
                .isEqualTo(16);
    }

    // V16 (Day 25): the key and the enum left project_db with the table. The harness puts a foreign
    // table over apps_db's in its place (TestDatabase), so that name may only be a foreign table.
    @Test
    void savedJobsLeftProjectDbWithItsKey() {
        assertThat(jdbc()
                .sql("""
                        SELECT (SELECT count(*) FROM pg_constraint WHERE conname = 'fk_saved_jobs_user')
                            || ' ' || coalesce(to_regtype('applications.job_state')::text, 'none')
                            || ' ' || coalesce((SELECT relkind::text FROM pg_class
                                                WHERE oid = to_regclass('applications.saved_jobs')), 'none')
                        """)
                .query(String.class)
                .single())
                .isIn("0 none f", "0 none none");
    }

    // matching-service has had no Flyway since Day 23: its schema is migrated by app's V15 alone.
    @Test
    void matchingKeepsNoHistoryOfItsOwn() {
        assertThat(jdbc()
                .sql("SELECT to_regclass('matching.flyway_schema_history') IS NULL")
                .query(Boolean.class)
                .single())
                .isTrue();
    }
}
