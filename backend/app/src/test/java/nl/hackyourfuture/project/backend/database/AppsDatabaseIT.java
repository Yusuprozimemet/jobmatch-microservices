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
 * Only applications_user may connect to apps_db: the other modules are refused at login, before
 * any schema grant is looked at. The admin is a superuser here, so no check can deny it the
 * database.
 */
class AppsDatabaseIT extends IntegrationTest {

    private final DataSource appsUserDataSource = new DriverManagerDataSource(
            PostgresContainer.appsJdbcUrl("applications"), "applications_user", PostgresContainer.rolePassword());

    @Test
    void applicationsUserCanConnectToAppsDatabase() {
        assertThat(JdbcClient.create(appsUserDataSource)
                .sql("SELECT current_user")
                .query(String.class)
                .single())
                .isEqualTo("applications_user");
    }

    @Test
    void applicationsUserConnectsToApplicationsSchema() {
        assertThat(JdbcClient.create(appsUserDataSource)
                .sql("SELECT current_schema()")
                .query(String.class)
                .single())
                .isEqualTo("applications");
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity_user", "matching_user", "jobs_user", "analytics_user"})
    void otherRolesCannotConnectToAppsDatabase(String role) {
        DataSource deniedDataSource = new DriverManagerDataSource(
                PostgresContainer.appsJdbcUrl("public"), role, PostgresContainer.rolePassword());
        assertThatThrownBy(() -> JdbcClient.create(deniedDataSource)
                .sql("SELECT current_user")
                .query(String.class)
                .single())
                .rootCause()
                .hasMessageContaining("permission denied for database \"apps_db\"");
    }
}
