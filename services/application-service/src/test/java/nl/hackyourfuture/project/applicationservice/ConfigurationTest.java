package nl.hackyourfuture.project.applicationservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code application.yaml} leaves to the environment and what it fixes. It is loaded as the
 * service loads it, so a default added there for either URL fails the first two.
 */
class ConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Urls.class);

    @Test
    void withoutAnIdentityUrlItDoesNotStart() {
        context.withPropertyValues("app.internal.jobs-url=http://jobs:8080")
                .run(started -> assertThat(started).getFailure().rootCause()
                        .hasMessageContaining("app.internal.identity-url"));
    }

    @Test
    void withoutAJobsUrlItDoesNotStart() {
        context.withPropertyValues("app.internal.identity-url=http://identity:8080")
                .run(started -> assertThat(started).getFailure().rootCause()
                        .hasMessageContaining("app.internal.jobs-url"));
    }

    @Test
    void withBothItStarts() {
        context.withPropertyValues("app.internal.identity-url=http://identity:8080", "app.internal.jobs-url=http://jobs:8080")
                .run(started -> assertThat(started.getBean(InternalUrls.class))
                        .isEqualTo(new InternalUrls("http://identity:8080", "http://jobs:8080")));
    }

    @EnableConfigurationProperties(InternalUrls.class)
    static class Urls {
    }
}
