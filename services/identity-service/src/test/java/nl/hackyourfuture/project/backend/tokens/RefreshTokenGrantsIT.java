package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only {@code identity} reads the refresh-token hashes (Day 12) and the pending Google links
 * (Day 14). Since Day 38 no other module reads any of its schema, and the refusal is the
 * schema's; {@code ModuleConnectionsIT} checks that nothing identity creates later is granted.
 *
 * <p>Since Day 42 no other role can log in to identity_db ({@code ModuleConnectionsIT}), so the
 * grants are read from the harness's connection: a role with no USAGE on the schema could not
 * read the tables whatever their own grants say.
 */
class RefreshTokenGrantsIT extends IntegrationTest {

    @Autowired
    private ApplicationContext context;

    @ParameterizedTest
    @CsvSource({"matching_user, identity.refresh_tokens",
        "matching_user, identity.pending_google_links",
        "jobs_user, identity.refresh_tokens",
        "jobs_user, identity.pending_google_links",
        "applications_user, identity.refresh_tokens",
        "applications_user, identity.pending_google_links"})
    void noOtherModuleCanReadThem(String role, String table) {
        assertThat(jdbc()
                .sql("SELECT has_schema_privilege(:role, 'identity', 'USAGE') OR has_table_privilege(:role, :table, 'SELECT')")
                .param("role", role)
                .param("table", table)
                .query(Boolean.class)
                .single())
                .as(role + " on " + table)
                .isFalse();
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
