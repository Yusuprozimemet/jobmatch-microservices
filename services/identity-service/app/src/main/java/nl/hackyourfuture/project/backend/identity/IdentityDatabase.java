package nl.hackyourfuture.project.backend.identity;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;

/**
 * Identity's own connection: it logs in as {@code identity_user}, with
 * {@code identity} as its search path (Day 11). A write into another module's schema is refused by
 * Postgres, not by convention.
 *
 * <p>Nothing here is primary, on purpose. A class that asks for a plain {@code JdbcClient}
 * fails at startup instead of quietly getting another module's connection.
 */
@Configuration(proxyBeanMethods = false)
class IdentityDatabase {

    @Bean(destroyMethod = "close")
    HikariDataSource identityDataSource(
            @Value("${app.datasource.identity.url}") String url,
            @Value("${app.datasource.identity.username}") String username,
            @Value("${app.datasource.identity.password}") String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("identity");
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        // Four pools where there was one, so each is small: at most five connections, one kept
        // idle. Hikari's default, ten held open each, is 40 per instance and ran a test run's
        // cached contexts out of Postgres's connection slots once all four pools opened at startup.
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(1);
        return dataSource;
    }

    @Bean
    JdbcClient identityJdbcClient(@Qualifier("identityDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    JdbcTransactionManager identityTransactionManager(@Qualifier("identityDataSource") DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
