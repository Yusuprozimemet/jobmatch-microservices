package nl.hackyourfuture.project.backend.database;

import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.PostgresContainer;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each module's connection is its own role, and Postgres, not convention, keeps it in its lane.
 *
 * <p>Not a contract test: it pins how the application reaches the database (Day 11). The roles
 * and schemas are real in every environment; only the passwords differ. These go through the
 * application's own pools, not the harness's connection, because a role that holds in the
 * database proves nothing if the application logs in as someone else. The jobs role is checked
 * through the harness's own login as jobs_user, since that login leaves this process; what the
 * service itself connects as is checked where it runs.
 */
class ModuleConnectionsIT extends IntegrationTest {

    @Autowired @Qualifier("identityDataSource") private DataSource identity;
    @Autowired @Qualifier("applicationsDataSource") private DataSource applications;
    @Autowired @Qualifier("matchingDataSource") private DataSource matching;
    private final DataSource jobs = new DriverManagerDataSource(
            PostgresContainer.jdbcUrl("analytics"), "jobs_user", PostgresContainer.rolePassword());

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
                .hasMessageContaining("permission denied for schema applications");
    }

    // Days 08-10 removed the reads; Day 38 revoked what allowed them, before the modules leave
    // the process with these logins.
    @ParameterizedTest
    @CsvSource({"identity, applications.saved_jobs", "jobs, identity.user_credentials",
        "applications, matching.job_match_scores", "matching, identity.users"})
    void noModuleCanReadAnothersSchema(String module, String table) {
        assertThatThrownBy(() -> JdbcClient.create(pool(module))
                .sql("SELECT count(*) FROM " + table)
                .query(Long.class)
                .single())
                .rootCause()
                .hasMessageContaining("permission denied for schema " + table.substring(0, table.indexOf('.')));
    }

    // Created through identity's own pool, so identity_user is its creator, as for a migration:
    // through the harness's connection, a superuser's, the table would get no grants either way.
    @Test
    void norATableAModuleCreatesLater() {
        JdbcClient owner = JdbcClient.create(identity);
        owner.sql("CREATE TABLE later_table (id INT)").update();
        try {
            for (String other : new String[] {"applications_user", "matching_user", "jobs_user"}) {
                assertThat(jdbc().sql("SELECT has_table_privilege(:role, 'identity.later_table', 'SELECT')")
                        .param("role", other)
                        .query(Boolean.class)
                        .single())
                        .as(other)
                        .isFalse();
            }
        } finally {
            owner.sql("DROP TABLE later_table").update();
        }
    }

    // Default privileges registered for any creator but the owner, db-setup.py's admin among them.
    @Test
    void noModuleSchemaGrantsWhatIsCreatedLaterToAnyoneElse() {
        assertThat(jdbc()
                .sql("""
                        SELECT n.nspname || ': ' || pg_get_userbyid(acl.grantee)
                        FROM pg_default_acl d JOIN pg_namespace n ON n.oid = d.defaclnamespace, aclexplode(d.defaclacl) acl
                        WHERE n.nspname IN ('identity', 'applications', 'matching') AND acl.grantee <> n.nspowner
                        """)
                .query(String.class)
                .list())
                .isEmpty();
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

    private DataSource pool(String module) {
        return switch (module) {
            case "identity" -> identity;
            case "applications" -> applications;
            case "matching" -> matching;
            case "jobs" -> jobs;
            default -> throw new IllegalArgumentException(module);
        };
    }

    private static String whoAndWhere(DataSource dataSource) {
        return JdbcClient.create(dataSource)
                .sql("SELECT current_user || ' ' || current_schema()")
                .query(String.class)
                .single();
    }
}
