package nl.hackyourfuture.project.matchingservice;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The one Postgres container the test suite shares, holding the matching schema and the role
 * matching_user.
 *
 * <p>A static singleton rather than a {@code @Bean} or {@code @Container} field on purpose:
 * Spring caches test contexts per configuration, so a container owned by a context is started
 * once per <em>context</em>, and a second context - which any {@code @MockitoBean} or extra
 * property creates - quietly starts a second database. This one is started once per JVM and
 * never stopped; Testcontainers' Ryuk sidecar removes it when the JVM exits.
 *
 * <p>In production the role and schema come from {@code scripts/db-setup.py} (compose:
 * {@code scripts/db-init/}), and the table from the monolith's {@code app} migrations, V10 and V14.
 * The service applies only {@code db/matching} (from Track E1), which starts from all three, so
 * its tests make them here, as the harness's {@code PostgresContainer} does.
 */
final class PostgresContainer {

    private static final String IMAGE = "postgres:18.4-alpine";

    private static final PostgreSQLContainer CONTAINER;

    static {
        CONTAINER = new PostgreSQLContainer(DockerImageName.parse(IMAGE))
                .withDatabaseName("project_db")
                .withUsername("app_user")
                .withPassword("password")
                .withCommand("postgres", "-c", "fsync=off");
        CONTAINER.start();
        createMatchingRoleAndSchema();
    }

    private PostgresContainer() {
    }

    /**
     * JDBC URL for connecting as matching_user with matching as the search path, so unqualified
     * table names resolve in that schema.
     */
    public static String jdbcUrl() {
        String url = CONTAINER.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=matching";
    }

    /** The username to connect as: matching_user. */
    public static String username() {
        return "matching_user";
    }

    /** The password to connect as matching_user. */
    public static String password() {
        return "password";
    }

    /** A connection as matching_user, as the service connects: for fixtures and assertions. */
    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), username(), password());
    }

    /** Runs a multi-statement script as the container's owner (app_user). */
    public static void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(
                CONTAINER.getJdbcUrl(),
                CONTAINER.getUsername(),
                CONTAINER.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to run SQL against the test database", e);
        }
    }

    private static void createMatchingRoleAndSchema() {
        StringBuilder script = new StringBuilder();
        script.append("CREATE ROLE matching_user LOGIN PASSWORD 'password';\n");
        script.append("CREATE SCHEMA matching AUTHORIZATION matching_user;\n");
        script.append(readSqlFixture("fixtures/matching-schema.sql"));

        execute(script.toString());
    }

    private static String readSqlFixture(String classpathLocation) {
        try (InputStream stream = PostgresContainer.class.getClassLoader()
                .getResourceAsStream(classpathLocation)) {
            if (stream == null) {
                throw new IllegalStateException("No such SQL fixture on the classpath: " + classpathLocation);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read SQL fixture " + classpathLocation, e);
        }
    }
}
