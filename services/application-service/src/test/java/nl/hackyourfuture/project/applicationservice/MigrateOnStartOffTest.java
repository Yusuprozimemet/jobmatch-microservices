package nl.hackyourfuture.project.applicationservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * With MIGRATE_ON_START=false, the service starts and answers health checks without
 * creating a Flyway history table, so a MIGRATE_ONLY run can do the migrations (C33.5).
 *
 * <p>Not an ApplicationServiceTest subclass: its database properties would win over this
 * class's, and the context would start on apps_db, migrated already, where nothing here
 * could fail.
 */
@SpringBootTest(classes = ApplicationServiceApplication.class, webEnvironment = RANDOM_PORT)
class MigrateOnStartOffTest {

    private static final String TEST_DATABASE = "migrate_on_start_off_db";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private DataSource dataSource;

    static {
        PostgresContainer.createDatabase(TEST_DATABASE);
    }

    @DynamicPropertySource
    static void useTestDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PostgresContainer.jdbcUrl(TEST_DATABASE, "applications"));
        registry.add("spring.datasource.username", PostgresContainer::role);
        registry.add("spring.datasource.password", PostgresContainer::rolePassword);
        registry.add("app.internal.identity-url", () -> StubUpstream.instance().baseUrl());
        registry.add("app.internal.jobs-url", () -> StubUpstream.instance().baseUrl());
        registry.add("app.identity.jwks-url", () -> TestIdentity.instance().url() + "/.well-known/jwks.json");
        registry.add("app.service-jwt.private-key-file", () -> TestKey.path().toString());
        registry.add("app.internal.job-service-key-set-url", () -> TestCallers.instance().jwksUrl(TestCallers.JOB_SERVICE));
        registry.add("app.internal.trusted-issuers[0].name", () -> TestCallers.CALLER);
        registry.add("app.internal.trusted-issuers[0].key-set-url", () -> TestCallers.instance().jwksUrl(TestCallers.CALLER));
        registry.add("MIGRATE_ON_START", () -> "false");
    }

    @Test
    void answersHealthCheckWithoutMigrations() throws IOException, InterruptedException {
        HttpResponse<String> response = get("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void doesNotCreateFlywayHistory() {
        assertThat(countTables(TEST_DATABASE,
                "SELECT count(*) FROM pg_tables WHERE tablename = 'flyway_schema_history'"))
                .isEqualTo(0);
    }

    @Test
    void itsDatabaseIsTheEmptyOne() throws SQLException {
        // Through the context's own pool: doesNotCreateFlywayHistory reads the test database
        // directly, and would pass whichever database the context had started on.
        try (Connection connection = dataSource.getConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT current_database()")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo(TEST_DATABASE);
        }
    }

    private int countTables(String database, String sql) {
        try (Connection connection = PostgresContainer.adminConnection(database);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check database", e);
        }
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
