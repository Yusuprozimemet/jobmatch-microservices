package nl.hackyourfuture.project.backend.applications;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;

/**
 * Applications' connection is application-service's one datasource, apps_db as
 * applications_user with schema applications (Day 25). The qualified names stay so the moved
 * classes are unedited. Nothing primary, so a class asking for a plain {@code JdbcClient} still
 * gets Boot's.
 */
@Configuration(proxyBeanMethods = false)
class ApplicationsDatabase {

    @Bean
    JdbcClient applicationsJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    JdbcTransactionManager applicationsTransactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
