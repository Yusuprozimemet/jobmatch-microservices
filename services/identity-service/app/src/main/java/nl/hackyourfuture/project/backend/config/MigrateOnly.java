package nl.hackyourfuture.project.backend.config;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Migrate the owner's and identity's schemas, then exit.
 *
 * <p>For an ECS one-off migrate task before the service tasks start (Day 33): the migrations
 * run once, in a process that exits with status 0 on success. The task needs only the two
 * logins (owner and identity), not the signing key or anything else.
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
     * Build a Spring context with only Flyway and the migration configuration. Command-line
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
     * A minimal Spring context with Flyway's auto-configuration and
     * {@link Migrations}, which runs the owner's and module Flyways.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(FlywayAutoConfiguration.class)
    @Import(Migrations.class)
    static class Context {
    }
}
