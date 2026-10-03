package nl.hackyourfuture.project.applicationservice;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * The one Postgres container the test suite shares, for tests that need apps_db.
 *
 * <p>A static singleton rather than a {@code @Bean} or {@code @Container} field on purpose:
 * Spring caches test contexts per configuration, so a container owned by a context is started
 * once per <em>context</em>, and a second context, which any {@code @MockitoBean} or extra
 * property creates, quietly starts a second database. This one is started once per JVM and
 * never stopped; Testcontainers' Ryuk sidecar removes it when the JVM exits.
 *
 * <p>The applications_user role and the apps_db database are created immediately after start
 * and before any test boots, in a static block, using the container's admin connection.
 * Production gets these from {@code scripts/db-setup.py}; the harness creates them here, as
 * {@code backend/app/src/test/.../PostgresContainer} does for project_db's module roles
 * (Day 20).
 */
final class PostgresContainer {

    // Same tag as docker-compose.yml's db service.
    private static final String IMAGE = "postgres:18.4-alpine";

    // Fresh on every run, and the admin is the container default: no password is committed.
    private static final String ROLE_PASSWORD = UUID.randomUUID().toString();

    private static final PostgreSQLContainer CONTAINER;

    static {
        CONTAINER = new PostgreSQLContainer(DockerImageName.parse(IMAGE));
        CONTAINER.start();

        // The role and the database, from the default database.
        // Each statement runs on its own in autocommit: CREATE DATABASE cannot run inside a
        // transaction.
        try (Connection admin = DriverManager.getConnection(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
             Statement stmt = admin.createStatement()) {
            stmt.execute("CREATE ROLE applications_user LOGIN PASSWORD '" + ROLE_PASSWORD + "'");
            stmt.execute("CREATE DATABASE apps_db");
            stmt.execute("REVOKE CONNECT ON DATABASE apps_db FROM PUBLIC");
            stmt.execute("GRANT CONNECT ON DATABASE apps_db TO applications_user");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set up role and database", e);
        }

        // The schema, in apps_db: applications_user has no CREATE on the database.
        try (Connection appsConnection = DriverManager.getConnection(appsUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
             Statement stmt = appsConnection.createStatement()) {
            stmt.execute("CREATE SCHEMA applications AUTHORIZATION applications_user");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create applications schema", e);
        }
    }

    private PostgresContainer() {
    }

    /** Base JDBC URL onto the apps database. */
    private static String appsUrl() {
        return "jdbc:postgresql://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(5432) + "/apps_db";
    }

    /** JDBC URL onto the apps database with the applications schema as the search path. */
    public static String appsJdbcUrl() {
        return appsUrl() + "?currentSchema=applications";
    }

    /** The applications_user role name. */
    public static String role() {
        return "applications_user";
    }

    /** The password for the applications_user role in the test container. */
    public static String rolePassword() {
        return ROLE_PASSWORD;
    }

    /** A connection to apps_db as the container admin (the container default). */
    public static Connection adminConnection() {
        try {
            return DriverManager.getConnection(appsUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to get admin connection to apps_db", e);
        }
    }
}
