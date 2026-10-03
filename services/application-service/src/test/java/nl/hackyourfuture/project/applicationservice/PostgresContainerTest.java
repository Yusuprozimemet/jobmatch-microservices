package nl.hackyourfuture.project.applicationservice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The apps_db the service tests run on is set up as production's: applications_user connects
 * with the applications schema as its search path, and a role without the grant is refused, since
 * CONNECT is revoked from PUBLIC (Day 20's jobs_db, Day 25's apps_db).
 */
class PostgresContainerTest {

    private String probeRole;
    private String probePassword;

    @Test
    void applicationsUserConnectsWithItsOwnSchema() throws SQLException {
        try (Connection conn = DriverManager.getConnection(PostgresContainer.appsJdbcUrl(),
                PostgresContainer.role(), PostgresContainer.rolePassword());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT current_user, current_schema()")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("applications_user");
            assertThat(rs.getString(2)).isEqualTo("applications");
        }
    }

    @Test
    void aRoleWithoutTheGrantIsRefused() throws SQLException {
        probeRole = "b3_probe_" + UUID.randomUUID().toString().replace("-", "");
        probePassword = UUID.randomUUID().toString();

        try (Connection admin = PostgresContainer.adminConnection();
             Statement stmt = admin.createStatement()) {
            stmt.execute("CREATE ROLE " + probeRole + " LOGIN PASSWORD '" + probePassword + "'");
        }

        assertThatThrownBy(() -> DriverManager.getConnection(PostgresContainer.appsJdbcUrl(), probeRole, probePassword))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied for database");
    }

    @AfterEach
    void cleanup() throws SQLException {
        if (probeRole != null) {
            try (Connection admin = PostgresContainer.adminConnection();
                 Statement stmt = admin.createStatement()) {
                stmt.execute("DROP ROLE " + probeRole);
            }
        }
    }
}
