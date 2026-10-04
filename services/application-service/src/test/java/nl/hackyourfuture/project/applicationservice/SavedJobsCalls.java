package nl.hackyourfuture.project.applicationservice;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * What the tests moved from the monolith on Day 25 did through its harness: a saved row in apps_db,
 * written as the owner; {@code GET /api/saved-jobs} as a user identity says exists; and
 * {@code POST /internal/saved-counts} with whatever header the test sends.
 */
final class SavedJobsCalls {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final int port;

    SavedJobsCalls(int port) {
        this.port = port;
    }

    record Response(int status, JsonNode body) {

        JsonNode at(String pointer) {
            return body.at(pointer);
        }
    }

    static void save(UUID userId, String postingId) throws SQLException {
        try (Connection admin = PostgresContainer.adminConnection();
             PreparedStatement stmt = admin.prepareStatement(
                     "INSERT INTO applications.saved_jobs (user_id, posting_id) VALUES (?, ?)")) {
            stmt.setObject(1, userId);
            stmt.setString(2, postingId);
            stmt.execute();
        }
    }

    static void deleteAll() throws SQLException {
        try (Connection admin = PostgresContainer.adminConnection();
             PreparedStatement stmt = admin.prepareStatement("DELETE FROM applications.saved_jobs")) {
            stmt.execute();
        }
    }

    /** As the user, after the stub says identity has them. */
    Response savedJobs(UUID userId) throws IOException, InterruptedException {
        StubUpstream.instance().answer("/internal/users/" + userId, 200, "{}");
        return send(HttpRequest.newBuilder(uri("/api/saved-jobs"))
                .header("Cookie", "access_token=" + TestIdentity.instance().token(userId))
                .GET());
    }

    /** {@code headers} as name, value pairs; none for an anonymous call. */
    Response savedCounts(Object body, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/internal/saved-counts"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return send(request);
    }

    private Response send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = response.body().isEmpty() ? JSON.missingNode() : JSON.readTree(response.body());
        return new Response(response.statusCode(), body);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
