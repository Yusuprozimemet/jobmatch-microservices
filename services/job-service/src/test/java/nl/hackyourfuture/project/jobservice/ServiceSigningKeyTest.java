package nl.hackyourfuture.project.jobservice;

import com.nimbusds.jose.JOSEException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * job-service's service key (Day 39) refuses to start without a usable key file. The rules are
 * identity's {@code SigningKey}'s, copied; this pins that the copy keeps them.
 */
class ServiceSigningKeyTest {

    @Test
    void refusesAnEmptyPath() {
        assertThatThrownBy(() -> new ServiceSigningKey(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVICE_JWT_PRIVATE_KEY_FILE is not set");
    }

    @Test
    void refusesAMissingFile() {
        assertThatThrownBy(() -> new ServiceSigningKey("/nonexistent/path/key.pem"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVICE_JWT_PRIVATE_KEY_FILE points at")
                .hasMessageContaining("cannot be read");
    }

    @Test
    void refusesAFileNotInPemFormat() {
        String path = TestKey.write("not a key\n").toString();

        assertThatThrownBy(() -> new ServiceSigningKey(path))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVICE_JWT_PRIVATE_KEY_FILE points at")
                .hasMessageContaining("not a PKCS#8 PEM private key");
    }

    @Test
    void refusesA1024BitKey() {
        String pem = TestKey.pem(TestKey.rsaKeyPair(1024).getPrivate().getEncoded());
        String path = TestKey.write(pem).toString();

        assertThatThrownBy(() -> new ServiceSigningKey(path))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVICE_JWT_PRIVATE_KEY_FILE points at")
                .hasMessageContaining("1024-bit key")
                .hasMessageContaining("2048 bits is the minimum");
    }

    @Test
    void acceptsA2048BitKeyAndSetsKeyIdToThumbprint() throws JOSEException {
        ServiceSigningKey key = new ServiceSigningKey(TestKey.path().toString());

        assertThat(key.keyId()).isEqualTo(key.publicJwk().computeThumbprint().toString());
    }
}
