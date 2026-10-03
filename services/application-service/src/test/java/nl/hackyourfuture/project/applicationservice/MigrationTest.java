package nl.hackyourfuture.project.applicationservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What V1 leaves in apps_db once the service has started: the saved_jobs table in the
 * applications schema, owned by its role, with a job_state type holding the monolith's values,
 * and the Flyway history. Read from the catalogue as the admin; written as applications_user.
 */
class MigrationTest extends ApplicationServiceTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void savedJobsIsInTheApplicationsSchemaOwnedByItsRole() throws SQLException {
        try (Connection admin = PostgresContainer.adminConnection();
             Statement stmt = admin.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='applications' AND tablename='saved_jobs'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("tableowner")).isEqualTo("applications_user");
        }
    }

    @Test
    void jobStateHasTheMonolithsValuesInOrder() throws SQLException {
        try (Connection admin = PostgresContainer.adminConnection();
             Statement stmt = admin.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT e.enumlabel FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid " +
                     "JOIN pg_namespace n ON n.oid=t.typnamespace " +
                     "WHERE t.typname='job_state' AND n.nspname='applications' ORDER BY e.enumsortorder")) {
            List<String> values = new ArrayList<>();
            while (rs.next()) {
                values.add(rs.getString("enumlabel"));
            }
            assertThat(values).containsExactly("SAVED", "APPLIED", "REJECTED", "ACCEPTED", "DECLINED");
        }
    }

    @Test
    void aRowWithoutAStateIsSaved() {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        UUID userId = UUID.randomUUID();
        String postingId = "posting-1";

        try {
            template.update(
                    "INSERT INTO saved_jobs (user_id, posting_id) VALUES (?, ?)",
                    userId, postingId);

            String state = template.queryForObject(
                    "SELECT job_state FROM saved_jobs WHERE user_id = ? AND posting_id = ?",
                    (rs, rowNum) -> rs.getString("job_state"),
                    userId, postingId);

            assertThat(state).isEqualTo("SAVED");

            // The primary key.
            assertThatThrownBy(() -> template.update(
                    "INSERT INTO saved_jobs (user_id, posting_id) VALUES (?, ?)",
                    userId, postingId))
                    .isInstanceOf(DuplicateKeyException.class);
        } finally {
            template.update("DELETE FROM saved_jobs WHERE user_id = ? AND posting_id = ?", userId, postingId);
        }
    }

    @Test
    void noKeyPointsAtUsers() throws SQLException {
        try (Connection admin = PostgresContainer.adminConnection();
             Statement stmt = admin.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM pg_constraint WHERE conrelid='applications.saved_jobs'::regclass AND contype='f'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(0);
        }
    }

    @Test
    void theHistoryIsInTheSchemaItMigrates() throws SQLException {
        try (Connection admin = PostgresContainer.adminConnection();
             Statement stmt = admin.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT version FROM applications.flyway_schema_history WHERE success ORDER BY installed_rank")) {
            List<String> versions = new ArrayList<>();
            while (rs.next()) {
                versions.add(rs.getString("version"));
            }
            assertThat(versions).containsExactly("1");
        }
    }
}
