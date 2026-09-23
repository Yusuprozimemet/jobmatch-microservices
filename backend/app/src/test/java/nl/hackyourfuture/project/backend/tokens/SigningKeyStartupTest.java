package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.identity.token.SigningKey;
import nl.hackyourfuture.project.backend.support.TestSigningKey;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Without a usable signing key the application does not start, and says which variable to set
 * (Day 12). It never generates a key of its own, which would sign everyone out on each restart.
 *
 * <p>Not an {@code IntegrationTest}: that harness always supplies a key. This starts a context
 * holding only the key, which is where startup fails first.
 */
class SigningKeyStartupTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SigningKey.class);

    @Test
    void refusesToStartWithNoKeyConfigured() {
        context.run(started -> assertThat(started).getFailure()
                .rootCause().hasMessageContaining("JWT_PRIVATE_KEY_FILE is not set"));
    }

    @Test
    void refusesToStartWhenTheFileIsMissing() {
        context.withPropertyValues("app.jwt.private-key-file=" + Path.of("no-such-key.pem").toAbsolutePath())
                .run(started -> assertThat(started).getFailure()
                        .rootCause().hasMessageContaining("JWT_PRIVATE_KEY_FILE points at")
                        .hasMessageContaining("cannot be read"));
    }

    @Test
    void refusesToStartWithAFileThatIsNotAKey() {
        Path file = TestSigningKey.write("not a key\n");

        context.withPropertyValues("app.jwt.private-key-file=" + file)
                .run(started -> assertThat(started).getFailure()
                        .rootCause().hasMessageContaining("JWT_PRIVATE_KEY_FILE points at")
                        .hasMessageContaining("not a PKCS#8 PEM private key"));
    }

    @Test
    void refusesToStartWithAKeyThatIsNotRsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        Path file = TestSigningKey.write(TestSigningKey.pem(generator.generateKeyPair().getPrivate().getEncoded()));

        context.withPropertyValues("app.jwt.private-key-file=" + file)
                .run(started -> assertThat(started).getFailure()
                        .rootCause().hasMessageContaining("JWT_PRIVATE_KEY_FILE points at")
                        .hasMessageContaining("not an RSA private key"));
    }

    @Test
    void refusesToStartWithAKeyShorterThan2048Bits() {
        Path file = TestSigningKey.write(TestSigningKey.pem(
                TestSigningKey.rsaKeyPair(1024).getPrivate().getEncoded()));

        context.withPropertyValues("app.jwt.private-key-file=" + file)
                .run(started -> assertThat(started).getFailure()
                        .rootCause().hasMessageContaining("JWT_PRIVATE_KEY_FILE points at a 1024-bit key"));
    }

    @Test
    void startsWithAnRsaKeyAndNamesItByItsThumbprint() {
        context.withPropertyValues("app.jwt.private-key-file=" + TestSigningKey.path())
                .run(started -> {
                    assertThat(started).hasNotFailed();
                    SigningKey key = started.getBean(SigningKey.class);
                    assertThat(key.keyId()).isEqualTo(key.publicJwk().computeThumbprint().toString());
                    assertThat(key.publicJwk().isPrivate()).isFalse();
                });
    }
}
