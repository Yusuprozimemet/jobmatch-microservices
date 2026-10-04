package nl.hackyourfuture.project.backend.config;

import nl.hackyourfuture.project.backend.support.TestServiceCaller;
import nl.hackyourfuture.project.backend.support.TestSigningKey;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The trusted-issuer list binds from environment variables, as compose and Kubernetes set them
 * (Day 17's choice 4), the same way job-service's own copy does. The internal-route integration
 * tests check the tokens over HTTP; this is the binding alone.
 */
class InternalCallersTest {

    private static final ServiceSigningKey KEY = new ServiceSigningKey(TestSigningKey.servicePath().toString());

    @Test
    void theListBindsFromEnvironmentVariables() {
        InternalCallers callers = new InternalCallers(KEY, environment(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, TestServiceCaller.ISSUER));

        assertThat(callers.issuers()).containsExactly(ServiceTokens.ISSUER, TestServiceCaller.ISSUER);
    }

    // Spring maps APP_INTERNAL_... to app.internal... only in the source it gives that name, so a
    // test that builds the source under another name binds nothing and proves nothing.
    @Test
    void onlyTheSystemEnvironmentSourceIsReadThatWay() {
        InternalCallers callers = new InternalCallers(KEY, environment("another-name", TestServiceCaller.ISSUER));

        assertThat(callers.issuers()).containsExactly(ServiceTokens.ISSUER);
    }

    @Test
    void aBlankNameOrUrlRefusesToStart() {
        StandardEnvironment blankName = environment(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, "");

        assertThatThrownBy(() -> new InternalCallers(KEY, blankName))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.internal.trusted-issuers");
    }

    @Test
    void withNoConfiguredIssuersOnlyTheMonolithIsTrusted() {
        InternalCallers callers = new InternalCallers(KEY, new StandardEnvironment());

        assertThat(callers.issuers()).containsExactly(ServiceTokens.ISSUER);
    }

    private static StandardEnvironment environment(String sourceName, String issuer) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(sourceName, Map.of(
                "APP_INTERNAL_TRUSTEDISSUERS_0_NAME", issuer,
                "APP_INTERNAL_TRUSTEDISSUERS_0_KEYSETURL", TestServiceCaller.instance().jwksUrl())));
        return environment;
    }
}
