package nl.hackyourfuture.project.backend.database;

import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.PostgresContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * through the harness's own login as jobs_user, since that login leaves this process; job-service's
 * own login is checked through pg_stat_statements, since the container logs in as its own.
 */
class ModuleConnectionsIT extends IntegrationTest {

    @Autowired @Qualifier("identityDataSource") private DataSource identity;
    private final DataSource jobs = new DriverManagerDataSource(
            PostgresContainer.jobsJdbcUrl("analytics"), "jobs_user", PostgresContainer.rolePassword());

    @Test
    void eachModuleLogsInAsItsOwnRoleWithItsOwnSchema() {
        assertThat(whoAndWhere(identity)).isEqualTo("identity_user identity");
        assertThat(whoAndWhere(jobs)).isEqualTo("jobs_user analytics");
    }

    @ParameterizedTest
    @ValueSource(strings = {"applications", "matching", "jobs"})
    void noOtherModuleCanConnectToIdentityDb(String module) {
        // applications_user since Day 25, matching_user and jobs_user since Day 42: Postgres refuses
        // at login, so what they could read there is moot.
        DataSource moduleOnIdentityDb = new DriverManagerDataSource(
                PostgresContainer.jdbcUrl("public"), module + "_user", PostgresContainer.rolePassword());
        assertThatThrownBy(() -> JdbcClient.create(moduleOnIdentityDb)
                .sql("SELECT 1")
                .query(Integer.class)
                .single())
                .rootCause()
                .hasMessageContaining("permission denied for database \"identity_db\"");
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
    // V16 carries applications' revokes, as V15 does matching's.
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

    @Test
    void jobServiceReadsTheMartAsJobsUser() {
        jdbc().sql("SELECT public.pg_stat_statements_reset()").query().singleRow();

        assertThat(anonymous().get("/api/jobs").status()).isEqualTo(200);

        var users = jdbc()
                .sql("""
                        SELECT DISTINCT userid::regrole::text FROM public.pg_stat_statements
                        WHERE query ILIKE '%fct_postings%' AND query NOT ILIKE '%pg_stat_statements%'
                          AND dbid = (SELECT oid FROM pg_database WHERE datname = :database)
                        """)
                .param("database", PostgresContainer.JOBS_DATABASE)
                .query(String.class)
                .list();
        assertThat(users).containsExactly("jobs_user");
    }

    private static String whoAndWhere(DataSource dataSource) {
        return JdbcClient.create(dataSource)
                .sql("SELECT current_user || ' ' || current_schema()")
                .query(String.class)
                .single();
    }
}
