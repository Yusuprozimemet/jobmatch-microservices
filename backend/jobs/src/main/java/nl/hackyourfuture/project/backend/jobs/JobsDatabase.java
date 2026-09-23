package nl.hackyourfuture.project.backend.jobs;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;

/**
 * Jobs' own connection: it logs in as {@code jobs_user}, which may only read (Day 11). jobs owns
 * no tables; it reads the {@code analytics} mart the data pipeline publishes, by qualified name.
 *
 * <p>The role is what refuses a write. A read-only pool would not: the driver applies read-only
 * only inside explicit transactions, and these statements autocommit.
 *
 * <p>Nothing here is primary, on purpose. A class that asks for a plain {@code JdbcClient}
 * fails at startup instead of quietly getting another module's connection.
 */
@Configuration(proxyBeanMethods = false)
class JobsDatabase {

    @Bean(destroyMethod = "close")
    HikariDataSource jobsDataSource(
            @Value("${app.datasource.jobs.url}") String url,
            @Value("${app.datasource.jobs.username}") String username,
            @Value("${app.datasource.jobs.password}") String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("jobs");
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    @Bean
    JdbcClient jobsJdbcClient(@Qualifier("jobsDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    JdbcTransactionManager jobsTransactionManager(@Qualifier("jobsDataSource") DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
