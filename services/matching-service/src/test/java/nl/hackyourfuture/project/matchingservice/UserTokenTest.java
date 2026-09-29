package nl.hackyourfuture.project.matchingservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user's access token, verified in the service against identity's key set, read from the
 * cookie only, with its {@code sub} as the {@code TokenSubject} a controller reads.
 */
class UserTokenTest extends MatchingServiceTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final TestIdentity IDENTITY = TestIdentity.instance();
    private static final UUID USER = UUID.randomUUID();

    @LocalServerPort
    private int port;

    @Test
    void aVerifiedCookieCarriesItsSubject() throws Exception {
        HttpResponse<String> response = get("Cookie", "access_token=" + IDENTITY.token(USER));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(USER.toString());
    }

    @Test
    void noTokenIs401() throws Exception {
        assertThat(get().statusCode()).isEqualTo(401);
    }

    @Test
    void aTokenThatDoesNotVerifyIs401() throws Exception {
        Duration valid = Duration.ofMinutes(5);
        for (String token : new String[] {
            "garbage",
            IDENTITY.token(USER, "jobmatch-identity", "jobmatch-api", Duration.ofMinutes(-5)),
            IDENTITY.token(USER, "jobmatch-identity", "jobmatch-internal", valid),
            IDENTITY.token(USER, "jobmatch-backend", "jobmatch-api", valid),
            IDENTITY.signedByAnotherKey(USER),
        }) {
            assertThat(get("Cookie", "access_token=" + token).statusCode()).as(token).isEqualTo(401);
        }
    }

    /**
     * Cookie only, as the monolith and the gateway read it (Day 21's decision): the gateway forwards
     * the cookie, and a header reader would be a way in that neither of them has.
     */
    @Test
    void theAuthorizationHeaderIsNotRead() throws Exception {
        assertThat(get("Authorization", "Bearer " + IDENTITY.token(USER)).statusCode()).isEqualTo(401);
    }

    /** The id is the token's {@code sub} (Day 41); {@code X-User-Id} neither logs in nor overrides it. */
    @Test
    void xUserIdIsNotRead() throws Exception {
        String other = UUID.randomUUID().toString();

        assertThat(get("X-User-Id", other).statusCode()).isEqualTo(401);
        assertThat(get("Cookie", "access_token=" + IDENTITY.token(USER), "X-User-Id", other).body()).isEqualTo(USER.toString());
    }

    private HttpResponse<String> get(String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test/subject")).GET();
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
