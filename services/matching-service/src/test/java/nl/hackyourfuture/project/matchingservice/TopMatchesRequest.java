package nl.hackyourfuture.project.matchingservice;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

/**
 * One top-matches request that makes every outgoing call: the user exists, has a profile, the
 * shortlist has one posting, and the model scores it. For the tests that read those calls'
 * metrics and spans.
 */
final class TopMatchesRequest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private final int port;
    private final int managementPort;

    TopMatchesRequest(int port, int managementPort) {
        this.port = port;
        this.managementPort = managementPort;
    }

    /** Stubs every upstream for a new user with a complete profile (five skills), asks for their top matches, and returns the answer. */
    HttpResponse<String> send(String postingId) throws IOException, InterruptedException {
        UUID userId = UUID.randomUUID();
        StubUpstream.instance().refuse("/internal/users/" + userId, 204);
        StubUpstream.instance().answer("/internal/profiles/" + userId, 200,
                "{\"skills\":[\"java\",\"sql\",\"docker\",\"git\",\"linux\"],\"preferredCity\":\"testville\"}");
        StubUpstream.instance().answer("/internal/postings/shortlist", 200, """
                [{"postingId":"%s","title":"Observed Engineer","company":"Observed","location":"testville",
                  "category":null,"postedDate":"2026-09-30","jobSkills":["java","sql"],
                  "matchedSkills":["java","sql"],"jobSkillCount":2}]""".formatted(postingId));
        StubLlm.instance().willScoreInPromptOrder(80);

        return get(port, "/api/jobs/top-matches", TestIdentity.instance().token(userId));
    }

    /** The management port's Prometheus page. */
    String prometheus() throws IOException, InterruptedException {
        return get(managementPort, "/actuator/prometheus", null).body();
    }

    private static HttpResponse<String> get(int port, String path, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Cookie", "access_token=" + token);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
