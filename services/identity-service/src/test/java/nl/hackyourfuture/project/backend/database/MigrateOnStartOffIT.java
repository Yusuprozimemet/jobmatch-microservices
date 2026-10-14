package nl.hackyourfuture.project.backend.database;

import nl.hackyourfuture.project.backend.BackendApplication;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.PostgresContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With MIGRATE_ON_START=false, the service starts and answers health checks without
 * creating a Flyway history table, so a MIGRATE_ONLY run can do the migrations (C33.5).
 *
 * <p>Not an IntegrationTest: its database properties would win over this class's, and the
 * context would start on identity_db, migrated already, where nothing here could fail.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MigrateOnStartOffIT {

    private static final String TEST_DATABASE = "migrate_on_start_off_db";

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private Environment environment;

    static {
        PostgresContainer.createDatabase(TEST_DATABASE);
    }

    @DynamicPropertySource
    static void useTestDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", () -> PostgresContainer.jdbcUrl(TEST_DATABASE, "app"));
        registry.add("spring.flyway.user", () -> PostgresContainer.instance().getUsername());
        registry.add("spring.flyway.password", () -> PostgresContainer.instance().getPassword());
        registry.add("app.datasource.identity.url", () -> PostgresContainer.jdbcUrl(TEST_DATABASE, "identity"));
        registry.add("app.datasource.identity.username", () -> "identity_user");
        registry.add("app.datasource.identity.password", PostgresContainer::rolePassword);
        registry.add("MIGRATE_ON_START", () -> "false");
        IntegrationTest.useServices(registry);
    }

    // Without this, every check below would pass on identity_db, which is migrated already.
    @Test
    void itsDatabaseIsTheEmptyOne() {
        assertThat(environment.getProperty("spring.flyway.url")).contains(TEST_DATABASE);
        assertThat(environment.getProperty("app.datasource.identity.url")).contains(TEST_DATABASE);
    }

    @Test
    void answersHealthCheckWithoutMigrations() {
        var response = management().get("/actuator/health/readiness");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/status").asString()).isEqualTo("UP");
    }

    @Test
    void doesNotCreateFlywayHistory() {
        assertThat(countTables(TEST_DATABASE, "SELECT count(*) FROM pg_tables WHERE tablename = 'flyway_schema_history'"))
                .isEqualTo(0);
        assertThat(countTables(TEST_DATABASE, "SELECT count(*) FROM pg_tables WHERE schemaname = 'app'"))
                .isEqualTo(0);
    }

    private int countTables(String database, String sql) {
        try (Connection connection = DriverManager.getConnection(
                PostgresContainer.jdbcUrl(database, "app"),
                PostgresContainer.instance().getUsername(),
                PostgresContainer.instance().getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to check database", e);
        }
    }

    private ApiClient management() {
        return ApiClient.onPort(managementPort);
    }
}
