package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.config.ServiceSigningKey;
import nl.hackyourfuture.project.backend.identity.token.SigningKey;
import nl.hackyourfuture.project.backend.support.TestSigningKey;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Without a usable service key (Day 39) the application does not start, and says which variable
 * to set, as for the user key (Day 12, {@code SigningKeyStartupTest}, which covers the checks).
 *
 * <p>Not an {@code IntegrationTest}: that harness always supplies a key. This starts a context
 * holding only the key, which is where startup fails first.
 */
class ServiceSigningKeyStartupTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ServiceSigningKey.class);

    @Test
    void refusesToStartWithNoKeyConfigured() {
        context.run(started -> assertThat(started).getFailure()
                .rootCause().hasMessageContaining("SERVICE_JWT_PRIVATE_KEY_FILE is not set"));
    }

    @Test
    void refusesToStartWithAFileThatIsNotAKey() {
        Path file = TestSigningKey.write("not a key\n");

        context.withPropertyValues("app.service-jwt.private-key-file=" + file)
                .run(started -> assertThat(started).getFailure()
                        .rootCause().hasMessageContaining("SERVICE_JWT_PRIVATE_KEY_FILE points at")
                        .hasMessageContaining("not a PKCS#8 PEM private key"));
    }

    @Test
    void startsWithItsOwnKeyNotTheUsersKey() {
        context.withPropertyValues("app.service-jwt.private-key-file=" + TestSigningKey.servicePath())
                .run(started -> {
                    assertThat(started).hasNotFailed();
                    ServiceSigningKey key = started.getBean(ServiceSigningKey.class);
                    String serviceKeyId = key.keyId();
                    assertThat(serviceKeyId).isEqualTo(key.publicJwk().computeThumbprint().toString());

                    String userKeyId = SigningKey.read("JWT_PRIVATE_KEY_FILE", TestSigningKey.path().toString()).getKeyID();
                    assertThat(serviceKeyId).isNotEqualTo(userKeyId);
                });
    }
}
