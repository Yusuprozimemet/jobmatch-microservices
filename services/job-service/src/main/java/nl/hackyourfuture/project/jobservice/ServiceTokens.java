package nl.hackyourfuture.project.jobservice;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import nl.hackyourfuture.project.backend.shared.internal.ServiceToken;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * Mints job-service's service tokens (Day 39) with its own service key, verifiable against
 * {@code GET /.well-known/service-jwks.json}.
 *
 * <p>RS256 signing is cheap enough per call, and a short life caps the damage from a leaked
 * token, so no caching.
 */
@Component
public class ServiceTokens implements ServiceToken {

    /** Who issued the token; what other services check it against. */
    public static final String ISSUER = "jobmatch-job-service";
    /** Who the token is for: jobmatch's own internal network. */
    public static final String AUDIENCE = "jobmatch-internal";
    /** Short on purpose: it caps the damage from a token that cannot be revoked. */
    public static final Duration LIFETIME = Duration.ofMinutes(5);

    private final ServiceSigningKey key;
    private final RSASSASigner signer;

    public ServiceTokens(ServiceSigningKey key) throws JOSEException {
        this.key = key;
        this.signer = new RSASSASigner(key.privateJwk());
    }

    /**
     * A signed token for this service to present to another. The subject is the service itself,
     * not a user: what a service trusts is the issuer.
     */
    @Override
    public String mint() {
        // Whole seconds, as the claims carry them, so exp - iat is exactly the lifetime.
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(ISSUER)
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(LIFETIME)))
                // RS256 is deterministic and iat is in seconds, so without a random id two tokens
                // minted for one service within a second would be the same token.
                .jwtID(UUID.randomUUID().toString())
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(key.keyId())
                .build();
        SignedJWT token = new SignedJWT(header, claims);
        try {
            token.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign a service token with key " + key.keyId(), e);
        }
        return token.serialize();
    }
}
