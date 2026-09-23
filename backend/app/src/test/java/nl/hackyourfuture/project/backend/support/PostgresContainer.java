package nl.hackyourfuture.project.backend.support;

import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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
 * the application queries {@code analytics.fct_postings} and Flyway does not create it. So are
 * the module roles and their schemas, which production gets from {@code scripts/db-setup.py}
 * and compose from {@code scripts/db-init/}: a precondition of the migrations, not their work.
 */
public final class PostgresContainer {

    // Same image tag as docker-compose.yml, so tests and local dev cannot disagree on
    // Postgres behaviour.
    private static final String IMAGE = "postgres:18.4-alpine";

    /** Each module's schema, owned by a role of the same name plus {@code _user}. */
    public static final List<String> MODULE_SCHEMAS = List.of("identity", "applications", "matching");

    /** Every schema a module's tables can be in, in the order unqualified names resolve. */
    public static final List<String> TABLE_SCHEMAS = List.of("identity", "applications", "matching", "app");

    // The one password every module role has here. Test-only; compose and production set their own.
    private static final String ROLE_PASSWORD = "password";

    private static final PostgreSQLContainer CONTAINER;
    private static final DataSource DATA_SOURCE;

    static {
        CONTAINER = new PostgreSQLContainer(DockerImageName.parse(IMAGE))
                .withDatabaseName("project_db")
                .withUsername("app_user")
                .withPassword("password")
                // pg_stat_statements lets a test count the statements a request ran without
                // touching the application's DataSource - see StatementCounter. Setting the
                // command replaces Testcontainers' default, so fsync=off is repeated here.
                .withCommand("postgres", "-c", "fsync=off",
                        "-c", "shared_preload_libraries=pg_stat_statements");
        CONTAINER.start();
        DATA_SOURCE = buildDataSource();
        // In public, not a module schema: the connection's first schema would otherwise get the
        // extension's view inside the schema Flyway owns.
        execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements SCHEMA public");
        execute(SqlScripts.read("fixtures/analytics-schema.sql"));
        createModuleRoles();
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

    /** JDBC URL looking in every module schema and then {@code app}, as the application connects. */
    public static String jdbcUrl() {
        String url = CONTAINER.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + String.join(",", TABLE_SCHEMAS);
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

    /**
     * The module roles, and a schema owned by each: identity_user owns identity, and so on.
     * jobs_user owns nothing; it only ever reads. Every other role may use a schema but not
     * create in it, the rule {@code db-setup.py} applies. Grants on the tables themselves come
     * with the migrations that move the tables in, since grants do not follow a moved table.
     */
    private static void createModuleRoles() {
        List<String> statements = new ArrayList<>();
        for (String schema : MODULE_SCHEMAS) {
            statements.add("CREATE ROLE " + schema + "_user LOGIN PASSWORD '" + ROLE_PASSWORD + "'");
        }
        statements.add("CREATE ROLE jobs_user LOGIN PASSWORD '" + ROLE_PASSWORD + "'");
        for (String schema : MODULE_SCHEMAS) {
            statements.add("CREATE SCHEMA " + schema + " AUTHORIZATION " + schema + "_user");
            statements.add("GRANT USAGE ON SCHEMA " + schema + " TO " + othersThan(schema));
        }
        execute(String.join(";\n", statements));
    }

    private static String othersThan(String schema) {
        List<String> roles = new ArrayList<>();
        for (String other : MODULE_SCHEMAS) {
            if (!other.equals(schema)) {
                roles.add(other + "_user");
            }
        }
        roles.add("jobs_user");
        return String.join(", ", roles);
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
