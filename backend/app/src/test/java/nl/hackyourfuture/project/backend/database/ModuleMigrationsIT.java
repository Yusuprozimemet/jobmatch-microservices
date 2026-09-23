package nl.hackyourfuture.project.backend.database;

import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each module keeps its own migration history, in its own schema, under its own role (Day 11).
 *
 * <p>app's history holds V1-V14, including the moves that brought each module's tables in. Each
 * module's own Flyway then baselines at 0, so what a module migrates from here, starting with
 * Day 12's {@code identity.refresh_tokens}, is recorded where only that module's login can write.
 */
class ModuleMigrationsIT extends IntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"identity", "applications", "matching"})
    void eachModuleHasItsOwnHistoryOwnedByItsRole(String module) {
        assertThat(jdbc()
                .sql("SELECT tableowner FROM pg_tables WHERE schemaname = :schema AND tablename = 'flyway_schema_history'")
                .param("schema", module)
                .query(String.class)
                .optional())
                .contains(module + "_user");
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "applications", "matching"})
    void andItStartsAtTheBaseline(String module) {
        assertThat(jdbc()
                .sql("SELECT version || ' ' || type FROM " + module + ".flyway_schema_history ORDER BY installed_rank")
                .query(String.class)
                .list())
                .containsExactly("0 BASELINE");
    }

    @Test
    void appKeepsTheHistoryOfEverythingBefore() {
        assertThat(jdbc()
                .sql("SELECT max(version::int) FROM app.flyway_schema_history WHERE success")
                .query(Integer.class)
                .single())
                .isEqualTo(14);
    }
}
