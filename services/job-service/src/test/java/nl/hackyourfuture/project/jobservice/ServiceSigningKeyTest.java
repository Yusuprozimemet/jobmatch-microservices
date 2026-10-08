package nl.hackyourfuture.project.jobservice;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * job-service's service key (Day 39) refuses to start without a usable key file. The rules are
 * identity's {@code SigningKey}'s, copied; this pins that the copy keeps them.
 */
class ServiceSigningKeyTest {

    @Test
    void refusesAnEmptyPath() {
        assertThatThrownBy(() -> new ServiceSigningKey("", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Neither SERVICE_JWT_PRIVATE_KEY_FILE nor SERVICE_JWT_PRIVATE_KEY is set");
    }

    @Test
    void refusesAMissingFile() {
        assertThatThrownBy(() -> new ServiceSigningKey("/nonexistent/path/key.pem", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVICE_JWT_PRIVATE_KEY_FILE points at")
                .hasMessageContaining("cannot be read");
    }

    @Test
    void refusesAFileNotInPemFormat() {
        String path = TestKey.write("not a key\n").toString();

        assertThatThrownBy(() -> new ServiceSigningKey(path, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVICE_JWT_PRIVATE_KEY_FILE points at")
                .hasMessageContaining("not a PKCS#8 PEM private key");
    }

    @Test
    void refusesA1024BitKey() {
        String pem = TestKey.pem(TestKey.rsaKeyPair(1024).getPrivate().getEncoded());
        String path = TestKey.write(pem).toString();

        assertThatThrownBy(() -> new ServiceSigningKey(path, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVICE_JWT_PRIVATE_KEY_FILE points at")
                .hasMessageContaining("1024-bit key")
                .hasMessageContaining("2048 bits is the minimum");
    }

    @Test
    void acceptsA2048BitKeyAndSetsKeyIdToThumbprint() throws JOSEException {
        ServiceSigningKey key = new ServiceSigningKey(TestKey.path().toString(), "");

        assertThat(key.keyId()).isEqualTo(key.publicJwk().computeThumbprint().toString());
    }

    @Test
    void refusesBothTheFileAndTheContent() throws IOException {
        String pem = pem();

        assertThatThrownBy(() -> new ServiceSigningKey(TestKey.path().toString(), pem))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVICE_JWT_PRIVATE_KEY_FILE and SERVICE_JWT_PRIVATE_KEY are both set");
    }

    @Test
    void acceptsTheKeyInTheVariableUnderTheFilesKeyIdAndPublishesIt() throws IOException, ParseException {
        String fromFile = new ServiceSigningKey(TestKey.path().toString(), "").keyId();

        ServiceSigningKey key = new ServiceSigningKey("", pem());

        assertThat(key.keyId()).isEqualTo(fromFile);
        assertThat(JWKSet.parse(new ServiceJwksController(key).serviceJwks()).getKeyByKeyId(fromFile)).isNotNull();
    }

    @Test
    void refusesAVariableThatIsNotAKeyWithoutRepeatingIt() {
        assertThatThrownBy(() -> new ServiceSigningKey("", "not-a-key-7f3a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVICE_JWT_PRIVATE_KEY holds a value, which is not a PKCS#8 PEM private key")
                .hasMessageNotContaining("not-a-key-7f3a");
    }

    @Test
    void refusesAVariableHoldingA1024BitKey() {
        String pem = TestKey.pem(TestKey.rsaKeyPair(1024).getPrivate().getEncoded());

        assertThatThrownBy(() -> new ServiceSigningKey("", pem))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVICE_JWT_PRIVATE_KEY holds a 1024-bit key; 2048 bits is the minimum");
    }

    private static String pem() throws IOException {
        return Files.readString(TestKey.path(), StandardCharsets.US_ASCII);
    }
}
