package nl.hackyourfuture.project.backend.matching;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;

/**
 * Matching's own connection: it logs in as {@code matching_user}, with
 * {@code matching} as its search path (Day 11). A write into another module's schema is refused by
 * Postgres, not by convention.
 *
 * <p>Nothing here is primary, on purpose. A class that asks for a plain {@code JdbcClient}
 * fails at startup instead of quietly getting another module's connection.
 */
@Configuration(proxyBeanMethods = false)
class MatchingDatabase {

    @Bean(destroyMethod = "close")
    HikariDataSource matchingDataSource(
            @Value("${app.datasource.matching.url}") String url,
            @Value("${app.datasource.matching.username}") String username,
            @Value("${app.datasource.matching.password}") String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("matching");
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    @Bean
    JdbcClient matchingJdbcClient(@Qualifier("matchingDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    JdbcTransactionManager matchingTransactionManager(@Qualifier("matchingDataSource") DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
