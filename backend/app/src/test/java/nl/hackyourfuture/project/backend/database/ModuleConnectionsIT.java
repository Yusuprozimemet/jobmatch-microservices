package nl.hackyourfuture.project.backend.database;

import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each module's connection is its own role, and Postgres, not convention, keeps it in its lane.
 *
 * <p>Not a contract test: it pins how the application reaches the database (Day 11). The roles
 * and schemas are real in every environment; only the passwords differ. These go through the
 * application's own pools, not the harness's connection, because a role that holds in the
 * database proves nothing if the application logs in as someone else.
 */
class ModuleConnectionsIT extends IntegrationTest {

    @Autowired @Qualifier("identityDataSource") private DataSource identity;
    @Autowired @Qualifier("applicationsDataSource") private DataSource applications;
    @Autowired @Qualifier("matchingDataSource") private DataSource matching;
    @Autowired @Qualifier("jobsDataSource") private DataSource jobs;

    @Test
    void eachModuleLogsInAsItsOwnRoleWithItsOwnSchema() {
        assertThat(whoAndWhere(identity)).isEqualTo("identity_user identity");
        assertThat(whoAndWhere(applications)).isEqualTo("applications_user applications");
        assertThat(whoAndWhere(matching)).isEqualTo("matching_user matching");
        assertThat(whoAndWhere(jobs)).isEqualTo("jobs_user analytics");
    }

    @Test
    void identityCannotWriteApplicationsTables() {
        TestUser user = aUser().create();

        assertThatThrownBy(() -> JdbcClient.create(identity)
                .sql("INSERT INTO applications.saved_jobs (user_id, posting_id) VALUES (:id, 'seed-0001')")
                .param("id", user.id())
                .update())
                // Spring's exception says "bad SQL grammar"; Postgres's own words are underneath.
                .rootCause()
                .hasMessageContaining("permission denied for table saved_jobs");
    }

    // Read-only across modules is the rule db-setup.py applies; Days 08-09 removed the reads.
    @Test
    void identityMayStillReadThem() {
        assertThat(JdbcClient.create(identity)
                .sql("SELECT count(*) FROM applications.saved_jobs")
                .query(Long.class)
                .single())
                .isZero();
    }

    // The role refuses it. A read-only pool would not have: the driver applies read-only only
    // inside explicit transactions, and a first version of this test asserting it found so.
    @Test
    void jobsCannotWriteTheMart() {
        assertThatThrownBy(() -> JdbcClient.create(jobs)
                .sql("DELETE FROM analytics.fct_postings")
                .update())
                .rootCause()
                .hasMessageContaining("permission denied for table fct_postings");
    }

    private static String whoAndWhere(DataSource dataSource) {
        return JdbcClient.create(dataSource)
                .sql("SELECT current_user || ' ' || current_schema()")
                .query(String.class)
                .single();
    }
}
