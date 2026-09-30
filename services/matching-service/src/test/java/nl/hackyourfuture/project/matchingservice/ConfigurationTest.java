package nl.hackyourfuture.project.matchingservice;

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

    /** 9 s of internal calls, a score read, 5 s to connect to the model, 13 s to read and two score writes: 28.5 s of the gateway's 30. */
    @Test
    void theModelsReadIsThirteenSeconds() {
        context.withPropertyValues("app.internal.identity-url=x", "app.internal.jobs-url=x")
                .run(started -> assertThat(started.getEnvironment().getProperty("app.llm.timeout-seconds")).isEqualTo("13"));
    }

    /** A score store call, retries included, is half a second of that sum, three times a request (Day 22). */
    @Test
    void theStoresApiCallTimeoutIsHalfASecond() {
        context.withPropertyValues("app.internal.identity-url=x", "app.internal.jobs-url=x")
                .run(started -> assertThat(started.getEnvironment().getProperty("app.scores.api-call-timeout")).isEqualTo("500ms"));
    }

    @EnableConfigurationProperties(InternalUrls.class)
    static class Urls {
    }
}
