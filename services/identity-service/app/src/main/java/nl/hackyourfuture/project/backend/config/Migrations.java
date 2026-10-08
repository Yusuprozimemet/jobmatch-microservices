package nl.hackyourfuture.project.backend.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 * {@code identity.refresh_tokens} is the first. jobs has none: it owns no tables. matching has
 * none since Day 23: matching-service keeps its scores in DynamoDB and has no database.
 * applications has none since Day 25: application-service migrates saved_jobs in its own database.
 *
 * <p>The identity module's Flyway takes the datasource login directly (not the pool bean) so that
 * a MIGRATE_ONLY run can supply just the logins, without needing the pool or the signing key
 * (Day 33, ECS one-off migrate task).
 */
@Configuration(proxyBeanMethods = false)
class Migrations {

    @Bean
    FlywayMigrationStrategy ownerThenModules(
            @Value("${app.datasource.identity.url}") String identityUrl,
            @Value("${app.datasource.identity.username}") String identityUser,
            @Value("${app.datasource.identity.password}") String identityPassword) {
        return owner -> {
            owner.migrate();
            migrate("identity", identityUrl, identityUser, identityPassword);
        };
    }

    private static void migrate(String module, String url, String user, String password) {
        Flyway.configure()
                .dataSource(url, user, password)
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
