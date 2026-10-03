package nl.hackyourfuture.project.applicationservice;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who application-service trusts, bound from environment variables as compose and Kubernetes set them
 * (Day 17's choice 4). {@code InternalAccessTest} checks the tokens over HTTP.
 */
class InternalCallersTest {

    private static final String JOB_SERVICE_URL = TestCallers.instance().jwksUrl(TestCallers.JOB_SERVICE);

    @Test
    void theListBindsFromEnvironmentVariables() {
        InternalCallers callers = new InternalCallers(JOB_SERVICE_URL, environment(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, TestCallers.CALLER));

        assertThat(callers.issuers()).containsExactly(TestCallers.JOB_SERVICE, TestCallers.CALLER);
    }

    // Spring maps APP_INTERNAL_... to app.internal... only in the source it gives that name, so a
    // test that builds the source under another name binds nothing and proves nothing.
    @Test
    void onlyTheSystemEnvironmentSourceIsReadThatWay() {
        InternalCallers callers = new InternalCallers(JOB_SERVICE_URL, environment("another-name", TestCallers.CALLER));

        assertThat(callers.issuers()).containsExactly(TestCallers.JOB_SERVICE);
    }

    @Test
    void itDoesNotStartWithoutJobServicesKeySet() {
        assertThatThrownBy(() -> new InternalCallers("", new StandardEnvironment()))
                .hasMessageContaining("JOB_SERVICE_KEY_SET_URL");
    }

    @Test
    void jobServiceIsNotAListEntry() {
        assertThatThrownBy(() -> new InternalCallers(JOB_SERVICE_URL, environment(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, TestCallers.JOB_SERVICE)))
                .hasMessageContaining("JOB_SERVICE_KEY_SET_URL");
    }

    private static StandardEnvironment environment(String sourceName, String issuer) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(sourceName, Map.of(
                "APP_INTERNAL_TRUSTEDISSUERS_0_NAME", issuer,
                "APP_INTERNAL_TRUSTEDISSUERS_0_KEYSETURL", TestCallers.instance().jwksUrl(issuer))));
        return environment;
    }
}
