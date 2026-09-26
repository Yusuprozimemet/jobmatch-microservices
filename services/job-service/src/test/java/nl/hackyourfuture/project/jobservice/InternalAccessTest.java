package nl.hackyourfuture.project.jobservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only the callers job-service trusts get past {@code /internal/**} (Day 17). No route is there
 * until Track D, so a trusted caller gets 404. Not 403: only an authenticated caller shows that
 * difference, which is what {@code denyAll()} or a wrong chain would give (Track A1).
 */
class InternalAccessTest extends JobServiceTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private ServiceTokens ownTokens;

    @ParameterizedTest
    @ValueSource(strings = {TestCallers.BACKEND, TestCallers.CALLER})
    void aTrustedCallerGetsPastTheChain(String issuer) throws Exception {
        assertThat(send("POST", "/internal/nothing", "Authorization", "Bearer " + TestCallers.instance().token(issuer)))
                .isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "expired", "another audience", "another key", "a stranger", "job-service itself"})
    void anyoneElseIs401(String kind) throws Exception {
        TestCallers callers = TestCallers.instance();
        String token = switch (kind) {
            case "none" -> null;
            case "expired" -> callers.expired(TestCallers.CALLER);
            case "another audience" -> callers.forAudience(TestCallers.CALLER, "someone-else");
            case "another key" -> callers.signedByAnotherKey(TestCallers.CALLER);
            case "a stranger" -> callers.token(TestCallers.STRANGER);
            default -> ownTokens.mint();
        };
        assertThat(send("POST", "/internal/nothing", "Authorization", token == null ? null : "Bearer " + token))
                .isEqualTo(401);
    }

    @Test
    void onlyTheHeaderIsRead() throws Exception {
        String token = TestCallers.instance().token(TestCallers.BACKEND);

        assertThat(send("POST", "/internal/nothing", "Cookie", "access_token=" + token)).isEqualTo(401);
    }

    @Test
    void thePublicChainIgnoresAServiceToken() throws Exception {
        String token = TestCallers.instance().token(TestCallers.BACKEND);

        assertThat(send("GET", "/api/jobs", "Authorization", "Bearer " + token)).isEqualTo(404);
    }

    private int send(String method, String path, String header, String value) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, method.equals("GET")
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json");
        if (value != null) {
            request.header(header, value);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
