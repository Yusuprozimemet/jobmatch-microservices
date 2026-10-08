package nl.hackyourfuture.project.backend.tokens;

import com.nimbusds.jose.jwk.JWKSet;
import nl.hackyourfuture.project.backend.identity.token.SigningKey;
import nl.hackyourfuture.project.backend.support.TestSigningKey;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
                .rootCause().hasMessageContaining("Neither JWT_PRIVATE_KEY_FILE nor JWT_PRIVATE_KEY is set"));
    }

    @Test
    void refusesToStartWithBothTheFileAndTheContent() throws IOException {
        context.withPropertyValues("app.jwt.private-key-file=" + TestSigningKey.path(), "app.jwt.private-key=" + pem())
                .run(started -> assertThat(started).getFailure()
                        .rootCause().hasMessageContaining("JWT_PRIVATE_KEY_FILE and JWT_PRIVATE_KEY are both set"));
    }

    @Test
    void startsWithTheKeyInTheVariableUnderTheFilesKeyId() throws IOException {
        String[] fromFile = new String[1];
        context.withPropertyValues("app.jwt.private-key-file=" + TestSigningKey.path())
                .run(started -> fromFile[0] = started.getBean(SigningKey.class).keyId());

        context.withPropertyValues("app.jwt.private-key=" + pem())
                .run(started -> {
                    assertThat(started).hasNotFailed();
                    SigningKey key = started.getBean(SigningKey.class);
                    assertThat(key.keyId()).isEqualTo(fromFile[0]);
                    // What JwksController serves; it is package-private in the identity module.
                    assertThat(new JWKSet(key.publicJwk()).getKeyByKeyId(fromFile[0])).isNotNull();
                });
    }

    @Test
    void refusesToStartWithAVariableThatIsNotAKeyAndDoesNotRepeatIt() {
        context.withPropertyValues("app.jwt.private-key=not-a-key-7f3a")
                .run(started -> assertThat(started).getFailure()
                        .rootCause().hasMessageContaining("JWT_PRIVATE_KEY holds a value, which is not a PKCS#8 PEM private key")
                        .hasMessageNotContaining("not-a-key-7f3a"));
    }

    @Test
    void refusesToStartWithAVariableHoldingAKeyShorterThan2048Bits() {
        context.withPropertyValues("app.jwt.private-key="
                        + TestSigningKey.pem(TestSigningKey.rsaKeyPair(1024).getPrivate().getEncoded()))
                .run(started -> assertThat(started).getFailure()
                        .rootCause().hasMessageContaining("JWT_PRIVATE_KEY holds a 1024-bit key"));
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

    private static String pem() throws IOException {
        return Files.readString(TestSigningKey.path(), StandardCharsets.US_ASCII);
    }
}
