package nl.hackyourfuture.project.matchingservice;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One top-matches request for a new user, with the upstream stubbed: the user exists, has five
 * skills, and the shortlist is the given postings, each matching "java" and "sql". For the score
 * store's tests (Day 22), which care about what is stored and when the model is asked, not the
 * ranking.
 */
record TopMatchesCall(int port, UUID userId) {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    static TopMatchesCall forNewUser(int port, String... postingIds) {
        UUID userId = UUID.randomUUID();
        StubUpstream.instance().refuse("/internal/users/" + userId, 204);
        StubUpstream.instance().answer("/internal/profiles/" + userId, 200,
                "{\"skills\":[\"java\",\"sql\",\"docker\",\"git\",\"linux\"],\"preferredCity\":\"testville\"}");
        StubUpstream.instance().answer("/internal/postings/shortlist", 200, Arrays.stream(postingIds)
                .map(id -> "{\"postingId\":\"" + id + "\",\"title\":\"Job " + id + "\",\"company\":\"Company\","
                        + "\"location\":\"testville\",\"category\":null,\"postedDate\":\"2026-09-30\","
                        + "\"jobSkills\":[\"java\",\"sql\"],\"matchedSkills\":[\"java\",\"sql\"],\"jobSkillCount\":2}")
                .collect(Collectors.joining(",", "[", "]")));
        return new TopMatchesCall(port, userId);
    }

    /** Sends the request; a status other than 200 fails the test. */
    JsonNode send() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/jobs/top-matches"))
                .header("Cookie", "access_token=" + TestIdentity.instance().token(userId))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new AssertionError("top-matches answered " + response.statusCode() + ": " + response.body());
        }
        return JSON.readTree(response.body());
    }
}
