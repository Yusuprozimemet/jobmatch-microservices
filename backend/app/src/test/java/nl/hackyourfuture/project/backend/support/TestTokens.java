package nl.hackyourfuture.project.backend.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import nl.hackyourfuture.project.backend.identity.token.AccessTokens;
import nl.hackyourfuture.project.backend.identity.token.SigningKey;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/** Access tokens the application would not issue today, for tests about what it does with them. */
public final class TestTokens {

    private TestTokens() {
    }

    /**
     * Signed with the application's own key and shaped like its tokens, so the only thing wrong
     * with it is the time: an hour old, well past the decoder's 60 seconds of allowed clock skew.
     */
    public static String expired(SigningKey key, UUID userId, String email) {
        Instant issued = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuer(AccessTokens.ISSUER)
                .audience(AccessTokens.AUDIENCE)
                .issueTime(Date.from(issued))
                .expirationTime(Date.from(issued.plus(AccessTokens.LIFETIME)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyId()).build(), claims);
        try {
            token.sign(new RSASSASigner(key.privateJwk()));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return token.serialize();
    }
}
