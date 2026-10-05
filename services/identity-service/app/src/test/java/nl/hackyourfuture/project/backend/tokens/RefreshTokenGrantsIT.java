package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.PostgresContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Only {@code identity} reads the refresh-token hashes (Day 12) and the pending Google links
 * (Day 14). Since Day 38 no other module reads any of its schema, and the refusal is the
 * schema's; {@code ModuleConnectionsIT} checks that nothing identity creates later is granted.
 *
 * <p>As the role each module logs in as: identity through the application's own pool,
 * the others as matching_user, whose login left with matching-service (Day 21). Not
 * applications_user: since Day 25 it cannot connect to identity_db at all.
 */
class RefreshTokenGrantsIT extends IntegrationTest {

    @Autowired
    private ApplicationContext context;

    @ParameterizedTest
    @CsvSource({"matching, refresh_tokens",
        "matching, pending_google_links"})
    void noOtherModuleCanReadThem(String module, String table) {
        DataSource dataSource = new DriverManagerDataSource(PostgresContainer.jdbcUrl(module), module + "_user", PostgresContainer.rolePassword());

        assertThatThrownBy(() -> JdbcClient.create(dataSource)
                .sql("SELECT count(*) FROM identity." + table)
                .query(Long.class)
                .single())
                .rootCause()
                .hasMessageContaining("permission denied for schema identity");
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
