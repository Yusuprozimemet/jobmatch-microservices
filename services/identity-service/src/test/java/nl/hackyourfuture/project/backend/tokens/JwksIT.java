package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /.well-known/jwks.json} publishes the key access tokens are verified with, and only
 * its public half (Day 12).
 */
class JwksIT extends IntegrationTest {

    // Every private member an RSA JWK can carry (RFC 7518 §6.3.2). Named one by one, so the test
    // does not depend on a library's idea of what is private.
    private static final String[] PRIVATE_MEMBERS = {"d", "p", "q", "dp", "dq", "qi", "oth"};

    @Test
    void isPublicAndServesOneRsaSigningKey() {
        ApiResponse response = anonymous().get("/.well-known/jwks.json");

        assertThat(response.status()).isEqualTo(200);
        JsonNode keys = response.at("/keys");
        assertThat(keys.size()).isEqualTo(1);
        JsonNode key = keys.get(0);
        assertThat(key.path("kty").asString()).isEqualTo("RSA");
        assertThat(key.path("use").asString()).isEqualTo("sig");
        assertThat(key.path("alg").asString()).isEqualTo("RS256");
        assertThat(key.path("kid").asString()).isNotBlank();
        assertThat(key.path("n").asString()).isNotBlank();
        assertThat(key.path("e").asString()).isNotBlank();
    }

    @Test
    void neverContainsThePrivateKey() {
        JsonNode key = anonymous().get("/.well-known/jwks.json").at("/keys/0");

        for (String member : PRIVATE_MEMBERS) {
            assertThat(key.has(member)).as("private member %s in the JWKS", member).isFalse();
        }
    }
}
