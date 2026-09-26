package nl.hackyourfuture.project.jobservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /.well-known/service-jwks.json} publishes the key service tokens are verified with,
 * and only its public half (Day 39).
 */
class ServiceKeySetTest extends JobServiceTest {

    // Every private member an RSA JWK can carry (RFC 7518 §6.3.2). Named one by one, so the test
    // does not depend on a library's idea of what is private.
    private static final String[] PRIVATE_MEMBERS = {"d", "p", "q", "dp", "dq", "qi", "oth"};
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private ServiceSigningKey serviceKey;

    @Test
    void anonymousGetsThePublicKey() throws Exception {
        HttpResponse<String> response = get("/.well-known/service-jwks.json");

        assertThat(response.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode keys = mapper.readTree(response.body()).get("keys");
        assertThat(keys).isNotNull();
        assertThat(keys.size()).isEqualTo(1);
    }

    @Test
    void onlyThePublicHalf() throws Exception {
        HttpResponse<String> response = get("/.well-known/service-jwks.json");

        assertThat(response.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode key = mapper.readTree(response.body())
            .get("keys").get(0);

        for (String member : PRIVATE_MEMBERS) {
            assertThat(key.has(member)).as("private member %s in the service JWKS", member).isFalse();
        }
    }

    @Test
    void theKeyIdEqualsTheSigningKeyThumbprint() throws Exception {
        HttpResponse<String> response = get("/.well-known/service-jwks.json");

        assertThat(response.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode key = mapper.readTree(response.body())
            .get("keys").get(0);

        assertThat(key.path("kid").asString())
                .isEqualTo(serviceKey.keyId());
    }

    @Test
    void theKeyIsRsaForSignature() throws Exception {
        HttpResponse<String> response = get("/.well-known/service-jwks.json");

        assertThat(response.statusCode()).isEqualTo(200);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode key = mapper.readTree(response.body())
            .get("keys").get(0);

        assertThat(key.path("kty").asString()).isEqualTo("RSA");
        assertThat(key.path("use").asString()).isEqualTo("sig");
        assertThat(key.path("alg").asString()).isEqualTo("RS256");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
