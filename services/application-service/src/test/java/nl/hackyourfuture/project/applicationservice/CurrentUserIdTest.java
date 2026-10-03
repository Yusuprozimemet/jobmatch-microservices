package nl.hackyourfuture.project.applicationservice;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deleted-user rule in application-service: the token's {@code sub} with identity asked
 * whether it still exists. A deleted account turns the user parameter empty; the controller
 * answers 404. StubUpstream is identity.
 */
class CurrentUserIdTest extends ApplicationServiceTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final TestIdentity IDENTITY = TestIdentity.instance();
    private static final StubUpstream STUB = StubUpstream.instance();
    private static final UUID USER = UUID.randomUUID();

    @LocalServerPort
    private int port;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setup() {
        STUB.reset();
        circuitBreakerRegistry.circuitBreaker("userExistence").reset();
    }

    @Test
    void aUserIdentityKnowsIsTheTokensSubject() throws Exception {
        STUB.answer("/internal/users/" + USER, 200, "{}");

        HttpResponse<String> response = get("/test/current-user");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(USER.toString());
        assertThat(STUB.calls("/internal/users/" + USER)).isEqualTo(1);
    }

    @Test
    void aUserIdentityDoesNotKnowIs404UserNotFound() throws Exception {
        STUB.refuse("/internal/users/" + USER, 404);

        HttpResponse<String> response = get("/test/current-user");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("User not found");
    }

    @ParameterizedTest
    @ValueSource(ints = {503, 500})
    void identityUnreachableWithStatusIs503(int status) throws Exception {
        STUB.refuse("/internal/users/" + USER, status);

        HttpResponse<String> response = get("/test/current-user");

        assertThat(response.statusCode()).isEqualTo(503);
    }

    @Test
    void identityUnreachableWithDropIs503() throws Exception {
        STUB.drop("/internal/users/" + USER);

        HttpResponse<String> response = get("/test/current-user");

        assertThat(response.statusCode()).isEqualTo(503);
    }

    @Test
    void identityHangingIs503() throws Exception {
        STUB.hang("/internal/users/" + USER);

        HttpResponse<String> response = get("/test/current-user");

        assertThat(response.statusCode()).isEqualTo(503);
    }

    @Test
    void aClientErrorFromIdentityIs500() throws Exception {
        STUB.refuse("/internal/users/" + USER, 400);

        HttpResponse<String> response = get("/test/current-user");

        assertThat(response.statusCode()).isEqualTo(500);
    }

    @Test
    void theBreakerOpensAndIdentityIsNoLongerCalled() throws Exception {
        STUB.refuse("/internal/users/" + USER, 503);

        for (int i = 0; i < 5; i++) {
            assertThat(get("/test/current-user").statusCode()).isEqualTo(503);
        }
        assertThat(STUB.calls("/internal/users/" + USER)).isEqualTo(5);

        HttpResponse<String> response = get("/test/current-user");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(STUB.calls("/internal/users/" + USER)).isEqualTo(5);
    }

    @Test
    void noTokenIs401AndIdentityIsNotAsked() throws Exception {
        STUB.answer("/internal/users/" + USER, 200, "{}");

        HttpResponse<String> response = noToken("/test/current-user");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(STUB.calls("/internal/users/" + USER)).isEqualTo(0);
    }

    @Test
    void aParameterThatIsNotAnOptionalUuidIs500() throws Exception {
        STUB.answer("/internal/users/" + USER, 200, "{}");

        HttpResponse<String> response = get("/test/wrong-type");

        assertThat(response.statusCode()).isEqualTo(500);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .header("Cookie", "access_token=" + IDENTITY.token(USER))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> noToken(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
