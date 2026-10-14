package nl.hackyourfuture.project.backend.database;

import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.PostgresContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Only job-service's and the publish's roles reach jobs_db: the other modules are refused at
 * login, before any schema grant is looked at. The admin is a superuser here, so no check can
 * deny it the database.
 */
class JobsDatabaseIT extends IntegrationTest {

    private final DataSource jobsUserDataSource = new DriverManagerDataSource(
            PostgresContainer.jobsJdbcUrl("analytics"), "jobs_user", PostgresContainer.rolePassword());
    private final DataSource analyticsUserDataSource = new DriverManagerDataSource(
            PostgresContainer.jobsJdbcUrl("analytics"), "analytics_user", PostgresContainer.rolePassword());

    @Test
    void jobsUserCanConnectToJobsDatabase() {
        assertThat(JdbcClient.create(jobsUserDataSource)
                .sql("SELECT current_user")
                .query(String.class)
                .single())
                .isEqualTo("jobs_user");
    }

    @Test
    void analyticsUserCanConnectToJobsDatabase() {
        assertThat(JdbcClient.create(analyticsUserDataSource)
                .sql("SELECT current_user")
                .query(String.class)
                .single())
                .isEqualTo("analytics_user");
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity_user", "applications_user", "matching_user"})
    void otherModulesCannotConnectToJobsDatabase(String role) {
        DataSource deniedDataSource = new DriverManagerDataSource(
                PostgresContainer.jobsJdbcUrl("public"), role, PostgresContainer.rolePassword());
        assertThatThrownBy(() -> JdbcClient.create(deniedDataSource)
                .sql("SELECT current_user")
                .query(String.class)
                .single())
                .rootCause()
                .hasMessageContaining("permission denied for database \"jobs_db\"");
    }
}
