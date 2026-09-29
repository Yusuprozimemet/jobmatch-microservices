package nl.hackyourfuture.project.matchingservice;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.text.ParseException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * matching-service's service tokens (Day 39): signed with the service key, verifiable against the
 * public half it publishes.
 */
class ServiceTokensTest {

    private ServiceSigningKey key;
    private ServiceTokens tokens;

    @BeforeEach
    void setUp() throws JOSEException {
        key = new ServiceSigningKey(TestKey.path().toString());
        tokens = new ServiceTokens(key);
    }

    @Test
    void aMintedTokenVerifiesWithThePublicKey() throws ParseException, JOSEException {
        String token = tokens.mint();

        SignedJWT jwt = (SignedJWT) JWTParser.parse(token);
        assertThat(jwt.verify(new RSASSAVerifier(key.publicJwk().toRSAKey()))).isTrue();
    }

    @Test
    void theTokenIssuerIsMatchingService() throws ParseException {
        String token = tokens.mint();

        SignedJWT jwt = (SignedJWT) JWTParser.parse(token);
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("jobmatch-matching-service");
    }

    @Test
    void theTokenSubjectIsMatchingService() throws ParseException {
        String token = tokens.mint();

        SignedJWT jwt = (SignedJWT) JWTParser.parse(token);
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("jobmatch-matching-service");
    }

    @Test
    void theTokenAudienceIsInternal() throws ParseException {
        String token = tokens.mint();

        SignedJWT jwt = (SignedJWT) JWTParser.parse(token);
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("jobmatch-internal");
    }

    @Test
    void theTokenLifetimeIsFiveMinutes() throws ParseException {
        String token = tokens.mint();

        SignedJWT jwt = (SignedJWT) JWTParser.parse(token);
        long expMinusIat = jwt.getJWTClaimsSet().getExpirationTime().getTime()
                - jwt.getJWTClaimsSet().getIssueTime().getTime();
        assertThat(expMinusIat).isEqualTo(300_000L);
    }

    @Test
    void eachCallMintsANewToken() {
        String token1 = tokens.mint();
        String token2 = tokens.mint();

        assertThat(token1).isNotEqualTo(token2);
    }

    @Test
    void theHeaderIncludesTheKeyId() throws ParseException {
        String token = tokens.mint();

        SignedJWT jwt = (SignedJWT) JWTParser.parse(token);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(key.keyId());
    }

    @Test
    void theHeaderTypeIsJwt() throws ParseException {
        String token = tokens.mint();

        SignedJWT jwt = (SignedJWT) JWTParser.parse(token);
        assertThat(jwt.getHeader().getType().toString()).isEqualTo("JWT");
    }
}
