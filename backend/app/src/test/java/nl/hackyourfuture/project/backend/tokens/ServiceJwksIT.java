package nl.hackyourfuture.project.backend.tokens;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.jwk.JWKSet;
import nl.hackyourfuture.project.backend.config.ServiceSigningKey;
import nl.hackyourfuture.project.backend.shared.internal.ServiceToken;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.text.ParseException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /.well-known/service-jwks.json} publishes the key service tokens are verified with,
 * and only its public half (Day 39).
 */
class ServiceJwksIT extends IntegrationTest {

    // Every private member an RSA JWK can carry (RFC 7518 §6.3.2). Named one by one, so the test
    // does not depend on a library's idea of what is private.
    private static final String[] PRIVATE_MEMBERS = {"d", "p", "q", "dp", "dq", "qi", "oth"};

    @Autowired
    private ServiceSigningKey serviceKey;

    @Autowired
    private ServiceToken serviceToken;

    @Test
    void isPublicAndServesOneRsaSigningKey() throws JOSEException {
        ApiResponse response = anonymous().get("/.well-known/service-jwks.json");

        assertThat(response.status()).isEqualTo(200);
        JsonNode keys = response.at("/keys");
        assertThat(keys.size()).isEqualTo(1);
        JsonNode key = keys.get(0);
        assertThat(key.path("kty").asString()).isEqualTo("RSA");
        assertThat(key.path("use").asString()).isEqualTo("sig");
        assertThat(key.path("alg").asString()).isEqualTo("RS256");
        assertThat(key.path("kid").asString())
                .isEqualTo(serviceKey.publicJwk().computeThumbprint().toString());
    }

    @Test
    void isNotTheUserKey() {
        JsonNode userKey = anonymous().get("/.well-known/jwks.json").at("/keys/0");
        JsonNode serviceKeyNode = anonymous().get("/.well-known/service-jwks.json").at("/keys/0");

        assertThat(serviceKeyNode.path("kid").asString())
                .isNotEqualTo(userKey.path("kid").asString());
    }

    @Test
    void neverContainsThePrivateKey() {
        JsonNode key = anonymous().get("/.well-known/service-jwks.json").at("/keys/0");

        for (String member : PRIVATE_MEMBERS) {
            assertThat(key.has(member)).as("private member %s in the service JWKS", member).isFalse();
        }
    }

    @Test
    void aMintedTokenVerifiesAgainstIt() throws ParseException, JOSEException {
        String token = serviceToken.mint();
        ApiResponse response = anonymous().get("/.well-known/service-jwks.json");
        JWKSet jwkSet = JWKSet.parse(response.body());

        SignedJWT jwt = (SignedJWT) JWTParser.parse(token);
        assertThat(jwt.verify(new RSASSAVerifier(jwkSet.getKeys().get(0).toRSAKey()))).isTrue();

        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("jobmatch-backend");
        assertThat(jwt.getJWTClaimsSet().getAudience()).contains("jobmatch-internal");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("jobmatch-backend");
        assertThat(jwt.getJWTClaimsSet().getClaim("email")).isNull();

        long expMinusIat = jwt.getJWTClaimsSet().getExpirationTime().getTime()
                - jwt.getJWTClaimsSet().getIssueTime().getTime();
        assertThat(expMinusIat).isLessThanOrEqualTo(300_000L).isGreaterThan(0);
    }

    @Test
    void eachCallMintsANewToken() {
        String token1 = serviceToken.mint();
        String token2 = serviceToken.mint();

        assertThat(token1).isNotEqualTo(token2);
    }
}
