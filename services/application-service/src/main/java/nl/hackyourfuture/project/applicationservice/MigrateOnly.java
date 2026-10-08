package nl.hackyourfuture.project.applicationservice;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Migrate the applications schema, then exit.
 *
 * <p>For an ECS one-off migrate task before the service tasks start (Day 33): the migrations
 * run once, in a process that exits with status 0 on success. The task needs only the database
 * login, not the signing key or anything else.
 */
public final class MigrateOnly {

    private MigrateOnly() {
    }

    /**
     * True for "true" (case-insensitive, trimmed); null/blank/other are false.
     */
    public static boolean requested(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.trim().equalsIgnoreCase("true");
    }

    /**
     * Build a Spring context with only the data source and Flyway. Command-line
     * arguments beat the yaml, so MIGRATE_ONLY wins over MIGRATE_ON_START=false. Flyway runs
     * during the context refresh, so a returned context means everything migrated.
     */
    public static ConfigurableApplicationContext migrate(String... args) {
        String[] migrateArgs = new String[args.length + 1];
        migrateArgs[0] = "--spring.flyway.enabled=true";
        System.arraycopy(args, 0, migrateArgs, 1, args.length);
        return new SpringApplicationBuilder(Context.class)
                .web(WebApplicationType.NONE)
                .run(migrateArgs);
    }

    /**
     * Run the migrations and exit 0 on success, 1 on failure.
     */
    public static int run(String... args) {
        try (var context = migrate(args)) {
            return 0;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /**
     * A minimal Spring context: the data source, as applications_user, and Flyway, which
     * migrates through it. No scan, so none of the service's beans or required settings.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    static class Context {
    }
}
