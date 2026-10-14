package nl.hackyourfuture.project.backend.database;

import nl.hackyourfuture.project.backend.config.MigrateOnly;
import nl.hackyourfuture.project.backend.support.PostgresContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A MIGRATE_ONLY run applies the owner's then identity's migrations, starts no web server,
 * and exits 0. A failed migration exits non-zero (C33.4).
 */
class MigrateOnlyIT {

    @Test
    void migratesTheOwnerThenIdentityAndStartsNoWebServer() {
        String database = "migrate_only_owner_identity_db";
        PostgresContainer.createDatabase(database);

        try (var context = MigrateOnly.migrate(args(database))) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(queryDatabase(database, "SELECT max(version::int) FROM app.flyway_schema_history WHERE success"))
                    .isEqualTo("16");
            assertThat(queryDatabaseRows(database, "SELECT version || ' ' || type FROM identity.flyway_schema_history ORDER BY installed_rank"))
                    .containsExactly("0 BASELINE", "1 SQL", "2 SQL", "3 SQL", "4 SQL");
        }
    }

    @Test
    void exitsZeroAndARerunChangesNothing() {
        String database = "migrate_only_rerun_db";
        PostgresContainer.createDatabase(database);

        assertThat(MigrateOnly.run(args(database))).isEqualTo(0);
        String historyBefore = queryDatabase(database, "SELECT count(*) FROM app.flyway_schema_history");
        String identityHistoryBefore = queryDatabase(database, "SELECT count(*) FROM identity.flyway_schema_history");

        assertThat(MigrateOnly.run(args(database))).isEqualTo(0);
        assertThat(queryDatabase(database, "SELECT count(*) FROM app.flyway_schema_history"))
                .isEqualTo(historyBefore);
        assertThat(queryDatabase(database, "SELECT count(*) FROM identity.flyway_schema_history"))
                .isEqualTo(identityHistoryBefore);
    }

    @Test
    void migratesEvenWithMigrateOnStartOff() {
        String database = "migrate_only_off_db";
        PostgresContainer.createDatabase(database);

        assertThat(MigrateOnly.run(args(database, "--MIGRATE_ON_START=false"))).isEqualTo(0);
        assertThat(queryDatabase(database, "SELECT max(version::int) FROM app.flyway_schema_history WHERE success"))
                .isEqualTo("16");
    }

    @Test
    void aFailedMigrationExitsNonZero() {
        String database = "migrate_only_fail_db";
        PostgresContainer.createDatabase(database);
        // In identity, not app: V1-V11 run, and V12's move of app.users into identity fails on it.
        // A table in app would stop Flyway before any migration, at its non-empty schema check.
        try (Connection connection = buildConnection(database, "identity");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE identity.users (id UUID PRIMARY KEY)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set up conflicting table", e);
        }

        assertThat(MigrateOnly.run(args(database))).isNotEqualTo(0);
        assertThat(queryDatabase(database, "SELECT max(version::int) FROM app.flyway_schema_history WHERE success"))
                .isEqualTo("11");
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

    private String[] args(String database, String... extra) {
        List<String> list = new ArrayList<>();
        list.add("--spring.flyway.url=" + PostgresContainer.jdbcUrl(database, "app"));
        list.add("--spring.flyway.user=" + PostgresContainer.instance().getUsername());
        list.add("--spring.flyway.password=" + PostgresContainer.instance().getPassword());
        list.add("--app.datasource.identity.url=" + PostgresContainer.jdbcUrl(database, "identity"));
        list.add("--app.datasource.identity.username=identity_user");
        list.add("--app.datasource.identity.password=" + PostgresContainer.rolePassword());
        list.add("--spring.profiles.active=test");
        list.addAll(List.of(extra));
        return list.toArray(new String[0]);
    }

    private String queryDatabase(String database, String sql) {
        try (Connection connection = buildConnection(database, "app");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (resultSet.next()) {
                return resultSet.getString(1);
            }
            return null;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query database", e);
        }
    }

    private List<String> queryDatabaseRows(String database, String sql) {
        List<String> rows = new ArrayList<>();
        try (Connection connection = buildConnection(database, "app");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                rows.add(resultSet.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query database", e);
        }
        return rows;
    }

    private static Connection buildConnection(String database, String schema) throws SQLException {
        return DriverManager.getConnection(
                PostgresContainer.jdbcUrl(database, schema),
                PostgresContainer.instance().getUsername(),
                PostgresContainer.instance().getPassword());
    }
}
