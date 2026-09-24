package nl.hackyourfuture.project.backend.config;

import com.nimbusds.jose.jwk.RSAKey;
import lombok.extern.slf4j.Slf4j;
import nl.hackyourfuture.project.backend.identity.token.SigningKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The RSA key the monolith signs its own service tokens with (Day 39): what proves the monolith
 * itself to another service, where identity's user key proves a user. A second key, not the user
 * key, so the two rotate apart.
 *
 * <p>The key is read from the PEM file {@code SERVICE_JWT_PRIVATE_KEY_FILE} names, with the same
 * checks as the user key: never generated at startup, only loaded from a file, and the
 * application does not start without a usable one.
 */
@Slf4j
@Component
public class ServiceSigningKey {

    private static final String VARIABLE = "SERVICE_JWT_PRIVATE_KEY_FILE";

    private final RSAKey key;

    public ServiceSigningKey(@Value("${app.service-jwt.private-key-file:}") String file) {
        this.key = SigningKey.read(VARIABLE, file);
        log.info("Signing service tokens with key {}", key.getKeyID());
    }

    /** The key id tokens carry in their header and the JWKS publishes. */
    public String keyId() {
        return key.getKeyID();
    }

    /** The private key, for signing. Never published. */
    public RSAKey privateJwk() {
        return key;
    }

    /** The public half, the only part that may leave this module. */
    public RSAKey publicJwk() {
        return key.toPublicJWK();
    }
}
