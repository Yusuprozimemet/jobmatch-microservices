package nl.hackyourfuture.project.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway answers a request that needs a login and has none itself, and decides who the
 * caller is for the services behind it (Day 15, Track B). Against a recording upstream: through
 * the real backend, its own 401 would pass for the gateway's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityTest {

    private static final RecordingUpstream BACKEND = new RecordingUpstream();
    private static final TestKeys KEYS = new TestKeys();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    // Every "required" row of the spec's table, and paths no rule names.
    private static final String[] PRIVATE = {"PATCH /api/auth/password", "GET /api/jobs/top-matches",
        "GET /api/profile", "PUT /api/profile", "GET /api/users/me", "DELETE /api/users/me",
        "GET /api/saved-jobs", "POST /api/jobs", "GET /api/anything-else", "GET /actuator/health"};
    private static final String[] PUBLIC = {"POST /api/auth/login", "POST /api/auth/register",
        "POST /api/auth/refresh", "POST /api/auth/logout", "GET /api/docs/openapi.yaml",
        "GET /api/oauth2/authorization/google", "GET /api/login/oauth2/code/google",
        "GET /.well-known/jwks.json", "GET /api/jobs", "GET /api/jobs/filters", "GET /api/jobs/abc-123"};

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("gateway.backend-url", BACKEND::url);
        registry.add("gateway.jwks-url", KEYS::jwksUrl);
    }

    @AfterAll
    static void stop() {
        BACKEND.close();
        KEYS.close();
    }

    @BeforeEach
    void clear() {
        BACKEND.clear();
    }

    @Test
    void aPrivateRouteWithNoCookieIsRefusedHere() throws Exception {
        for (String route : PRIVATE) {
            assertThat(send(route, null).statusCode()).as(route).isEqualTo(401);
        }
        assertThat(BACKEND.received()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "another key", "another audience", "another issuer", "garbage"})
    void aPrivateRouteWithAStaleCookieIsRefusedHere(String kind) throws Exception {
        for (String route : PRIVATE) {
            assertThat(send(route, KEYS.stale(kind)).statusCode()).as(route).isEqualTo(401);
        }
        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void aPrivateRouteWithAValidCookieReachesTheBackend() throws Exception {
        send("GET /api/profile", KEYS.valid(UUID.randomUUID()));

        assertThat(BACKEND.received()).extracting(RecordingUpstream.Received::pathAndQuery).containsExactly("/api/profile");
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "expired", "another key", "garbage"})
    void aPublicRouteReachesTheBackendWithOrWithoutAStaleCookie(String kind) throws Exception {
        for (String route : PUBLIC) {
            assertThat(send(route, "none".equals(kind) ? null : KEYS.stale(kind)).statusCode()).as(route).isEqualTo(200);
        }
        assertThat(BACKEND.received()).hasSize(PUBLIC.length);
    }

    @Test
    void aClientsUserIdIsReplacedByTheTokens() throws Exception {
        UUID caller = UUID.randomUUID();

        send("GET /api/profile", KEYS.valid(caller), "X-User-Id", UUID.randomUUID().toString(),
                "x-user-id", UUID.randomUUID().toString());

        assertThat(BACKEND.received()).singleElement()
                .satisfies(request -> assertThat(request.headers().get(Routes.USER_ID)).containsExactly(caller.toString()));
    }

    @Test
    void aClientsUserIdOnAPublicRouteIsDropped() throws Exception {
        send("GET /api/jobs", null, "X-User-Id", UUID.randomUUID().toString());
        send("GET /api/jobs", KEYS.stale("expired"), "x-user-id", UUID.randomUUID().toString());

        assertThat(BACKEND.received()).hasSize(2)
                .allSatisfy(request -> assertThat(request.headers().containsKey(Routes.USER_ID)).isFalse());
    }

    private HttpResponse<String> send(String route, String cookie, String... headers)
            throws IOException, InterruptedException {
        String[] methodAndPath = route.split(" ");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + methodAndPath[1]))
                .method(methodAndPath[0], HttpRequest.BodyPublishers.noBody());
        if (cookie != null) {
            request.header("Cookie", Security.ACCESS_COOKIE + "=" + cookie);
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
