package nl.hackyourfuture.project.backend.support;

import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The one Postgres container the whole test run shares.
 *
 * <p>A static singleton rather than a {@code @Bean} or {@code @Container} field on purpose:
 * Spring caches test contexts per configuration, so a container owned by a context is started
 * once per <em>context</em>, and a second context - which any {@code @MockitoBean} or extra
 * property creates - quietly starts a second database. This one is started once per JVM and
 * never stopped; Testcontainers' Ryuk sidecar removes it when the JVM exits.
 *
 * <p>The mart schema is created here, immediately after start and before Spring boots, because
 * the application queries {@code analytics.fct_postings} and Flyway does not create it.
 */
public final class PostgresContainer {

    // Same image tag as docker-compose.yml, so tests and local dev cannot disagree on
    // Postgres behaviour.
    private static final String IMAGE = "postgres:18.4-alpine";

    private static final PostgreSQLContainer CONTAINER;
    private static final DataSource DATA_SOURCE;

    static {
        CONTAINER = new PostgreSQLContainer(DockerImageName.parse(IMAGE))
                .withDatabaseName("project_db")
                .withUsername("app_user")
                .withPassword("password");
        CONTAINER.start();
        DATA_SOURCE = buildDataSource();
        execute(SqlScripts.read("fixtures/analytics-schema.sql"));
    }

    private PostgresContainer() {
    }

    /** Started, ready, and the same instance for every test class in the run. */
    public static PostgreSQLContainer instance() {
        return CONTAINER;
    }

    /**
     * A data source onto the container, independent of the application context.
     *
     * <p>Fixtures and assertions use this rather than the application's own
     * {@code JdbcClient}, so that setting up a test never depends on the beans the test is
     * about to exercise - and so this harness keeps working once those beans move to another
     * service.
     */
    public static DataSource dataSource() {
        return DATA_SOURCE;
    }

    /** JDBC URL with {@code currentSchema=app}, matching how the application connects. */
    public static String jdbcUrl() {
        String url = CONTAINER.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=app";
    }

    /** Runs a multi-statement script. Postgres accepts these over the simple query protocol. */
    public static void execute(String sql) {
        try (Connection connection = DATA_SOURCE.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to run SQL against the test database", e);
        }
    }

    private static DataSource buildDataSource() {
        var dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(jdbcUrl());
        dataSource.setUsername(CONTAINER.getUsername());
        dataSource.setPassword(CONTAINER.getPassword());
        return dataSource;
    }
}
