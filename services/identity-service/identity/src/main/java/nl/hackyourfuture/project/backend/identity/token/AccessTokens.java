package nl.hackyourfuture.project.backend.identity.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * Mints the access tokens {@code identity} issues (Day 12): RS256, fifteen minutes, verifiable by
 * anyone holding the key the JWKS publishes.
 *
 * <p>Nothing calls this yet. Day 13 issues a token at login and authenticates with it; until then
 * the session is the only credential and no response changes.
 */
@Component
public class AccessTokens {

    /** Who issued the token; what Day 13 and the gateway check it against. */
    public static final String ISSUER = "jobmatch-identity";
    /** Who the token is for: the API, whichever service ends up answering. */
    public static final String AUDIENCE = "jobmatch-api";
    /** Short on purpose: it caps the damage from a token that cannot be revoked. */
    public static final Duration LIFETIME = Duration.ofMinutes(15);

    private final SigningKey key;
    private final RSASSASigner signer;

    public AccessTokens(SigningKey key) throws JOSEException {
        this.key = key;
        this.signer = new RSASSASigner(key.privateJwk());
    }

    /**
     * A signed token for this user. The subject is the id, never the email: an email can change
     * and be taken by someone else; the id cannot.
     */
    public String mint(UUID userId, String email) {
        // Whole seconds, as the claims carry them, so exp - iat is exactly the lifetime.
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(LIFETIME)))
                // RS256 is deterministic and iat is in seconds, so without a random id two tokens
                // minted for one user within a second would be the same token.
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
            throw new IllegalStateException("Could not sign an access token with key " + key.keyId(), e);
        }
        return token.serialize();
    }
}
