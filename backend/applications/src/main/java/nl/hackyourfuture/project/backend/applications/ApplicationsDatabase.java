package nl.hackyourfuture.project.backend.applications;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;

/**
 * Applications' own connection: it logs in as {@code applications_user},
 * with {@code applications} as its search path (Day 11). A write into another module's schema is
 * refused by Postgres, not by convention.
 *
 * <p>Nothing here is primary, on purpose. A class that asks for a plain {@code JdbcClient}
 * fails at startup instead of quietly getting another module's connection.
 */
@Configuration(proxyBeanMethods = false)
class ApplicationsDatabase {

    @Bean(destroyMethod = "close")
    HikariDataSource applicationsDataSource(
            @Value("${app.datasource.applications.url}") String url,
            @Value("${app.datasource.applications.username}") String username,
            @Value("${app.datasource.applications.password}") String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("applications");
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
    JdbcClient applicationsJdbcClient(@Qualifier("applicationsDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    JdbcTransactionManager applicationsTransactionManager(@Qualifier("applicationsDataSource") DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
