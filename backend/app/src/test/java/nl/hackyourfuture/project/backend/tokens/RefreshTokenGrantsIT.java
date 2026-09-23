package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Only {@code identity} reads the refresh-token hashes, although every other module may read the
 * rest of its schema (Day 12).
 *
 * <p>Through the application's own pools, as {@code ModuleConnectionsIT} does, so the role refused
 * is the one each module really logs in as.
 */
class RefreshTokenGrantsIT extends IntegrationTest {

    @Autowired
    private ApplicationContext context;

    // Without these the refusal below would hold by accident: nobody would have been granted
    // anything to revoke. The harness registers them as db-setup.py does in production.
    @Test
    void theOthersAreGrantedWhatIdentityCreates() {
        assertThat(jdbc()
                .sql("""
                        SELECT DISTINCT pg_get_userbyid(acl.grantee)
                        FROM pg_default_acl d, aclexplode(d.defaclacl) acl
                        WHERE d.defaclrole = 'identity_user'::regrole AND d.defaclnamespace = 'identity'::regnamespace
                          AND d.defaclobjtype = 'r' AND acl.privilege_type = 'SELECT'
                        ORDER BY 1
                        """)
                .query(String.class)
                .list())
                .containsExactly("applications_user", "jobs_user", "matching_user");
    }

    @ParameterizedTest
    @ValueSource(strings = {"applications", "matching", "jobs"})
    void noOtherModuleCanReadThem(String module) {
        DataSource dataSource = context.getBean(module + "DataSource", DataSource.class);

        assertThatThrownBy(() -> JdbcClient.create(dataSource)
                .sql("SELECT count(*) FROM identity.refresh_tokens")
                .query(Long.class)
                .single())
                .rootCause()
                .hasMessageContaining("permission denied for table refresh_tokens");
    }

    @Test
    void identityCan() {
        DataSource identity = context.getBean("identityDataSource", DataSource.class);

        assertThat(JdbcClient.create(identity)
                .sql("SELECT count(*) FROM refresh_tokens")
                .query(Long.class)
                .single())
                .isZero();
    }
}
