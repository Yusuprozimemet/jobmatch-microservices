package nl.hackyourfuture.project.applicationservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A MIGRATE_ONLY run applies the applications migrations as applications_user, starts no web
 * server, and exits 0. A failed run exits non-zero (C33.4). Each test has a database of its own,
 * read as the admin.
 */
class MigrateOnlyTest {

    @Test
    void migratesApplicationsAndStartsNoWebServer() {
        String database = "migrate_only_apps_db";
        PostgresContainer.createDatabase(database);

        try (var context = MigrateOnly.migrate(args(database))) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(rows(database, "SELECT version || ' ' || type FROM applications.flyway_schema_history ORDER BY installed_rank"))
                    .containsExactly("1 SQL");
            assertThat(rows(database, "SELECT tableowner FROM pg_tables WHERE schemaname = 'applications' AND tablename = 'saved_jobs'"))
                    .containsExactly("applications_user");
        }
    }

    @Test
    void exitsZeroAndARerunChangesNothing() {
        String database = "migrate_only_rerun_db";
        PostgresContainer.createDatabase(database);

        assertThat(MigrateOnly.run(args(database))).isEqualTo(0);
        assertThat(MigrateOnly.run(args(database))).isEqualTo(0);
        assertThat(rows(database, "SELECT version FROM applications.flyway_schema_history ORDER BY installed_rank"))
                .containsExactly("1");
    }

    @Test
    void migratesEvenWithMigrateOnStartOff() {
        String database = "migrate_only_off_db";
        PostgresContainer.createDatabase(database);

        assertThat(MigrateOnly.run(args(database, "--MIGRATE_ON_START=false"))).isEqualTo(0);
        assertThat(rows(database, "SELECT version FROM applications.flyway_schema_history WHERE success"))
                .containsExactly("1");
    }

    @Test
    void aFailedRunExitsNonZero() {
        String database = "migrate_only_fail_db";
        PostgresContainer.createDatabase(database);
        // Without CREATE on its schema, applications_user cannot create the history table, so
        // the run fails before V1. A conflicting table would not fail V1 either: Flyway stops
        // first at its non-empty schema check.
        execute(database, "REVOKE CREATE ON SCHEMA applications FROM applications_user");

        assertThat(MigrateOnly.run(args(database))).isNotEqualTo(0);
        assertThat(rows(database, "SELECT tablename FROM pg_tables WHERE schemaname = 'applications'"))
                .isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "true, true",
            "TRUE, true",
            "' true ', true",
            "null, false",
            "'', false",
            "false, false",
            "yes, false"
    })
    void requested(String value, String expected) {
        String testValue = "null".equals(value) ? null : value;
        assertThat(MigrateOnly.requested(testValue)).isEqualTo(Boolean.parseBoolean(expected));
    }

    private static String[] args(String database, String... extra) {
        List<String> list = new ArrayList<>();
        list.add("--spring.datasource.url=" + PostgresContainer.jdbcUrl(database, "applications"));
        list.add("--spring.datasource.username=" + PostgresContainer.role());
        list.add("--spring.datasource.password=" + PostgresContainer.rolePassword());
        list.addAll(List.of(extra));
        return list.toArray(new String[0]);
    }

    private static List<String> rows(String database, String sql) {
        List<String> rows = new ArrayList<>();
        try (Connection admin = PostgresContainer.adminConnection(database);
             Statement statement = admin.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                rows.add(resultSet.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query " + database, e);
        }
        return rows;
    }

    private static void execute(String database, String sql) {
        try (Connection admin = PostgresContainer.adminConnection(database);
             Statement statement = admin.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to run on " + database, e);
        }
    }
}
