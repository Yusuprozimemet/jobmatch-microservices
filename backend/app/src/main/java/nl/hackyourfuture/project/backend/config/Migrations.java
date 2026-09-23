package nl.hackyourfuture.project.backend.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * The migrations, in the order Day 11 needs them.
 *
 * <p>First {@code app}'s V1-V14, by Spring Boot's own Flyway logging in as the owner: those
 * created every table and then moved each into its module's schema. Then one Flyway per module,
 * logging in as the module's role, with its own location ({@code db/identity}, ...) and its own
 * history table in its own schema. From here on a module's tables change through its own
 * migrations, which no other module's login could apply.
 *
 * <p>Each module instance baselines at version 0 on its first run, since its schema already holds
 * the tables the moves brought in, so the module's own V1 still runs. Day 12's
 * {@code identity.refresh_tokens} is the first. jobs has none: it owns no tables.
 */
@Configuration(proxyBeanMethods = false)
class Migrations {

    @Bean
    FlywayMigrationStrategy ownerThenModules(
            @Qualifier("identityDataSource") DataSource identity,
            @Qualifier("applicationsDataSource") DataSource applications,
            @Qualifier("matchingDataSource") DataSource matching) {
        return owner -> {
            owner.migrate();
            migrate("identity", identity);
            migrate("applications", applications);
            migrate("matching", matching);
        };
    }

    private static void migrate(String module, DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(module)
                .createSchemas(false)
                .locations("classpath:db/" + module)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                // No apostrophe: Flyway writes this into its history through a script, unescaped.
                .baselineDescription("tables moved in by V12-V14 in app")
                .load()
                .migrate();
    }
}
