package nl.hackyourfuture.project.matchingservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The stub model answers in the chat-completions shape the tests moving here rely on. */
class StubLlmTest {

    private static final String PROMPT = "Rank these.\nJobs:\n\nj1 | Java Dev | java\nj2 | SQL Dev | sql\n"
            + "j3 | Go Dev | go\n\nAnswer as JSON.";

    private final StubLlm stub = StubLlm.instance();
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void resetTheStub() {
        stub.reset();
    }

    // A blank line after "Jobs:" is skipped, as the harness's stub skips it.
    @Test
    void scoresTheJobsInPromptOrderAndLeavesTheRestUnscored() throws Exception {
        stub.willScoreInPromptOrder(80, 40);

        JsonNode scores = scores(PROMPT);

        assertThat(scores.size()).isEqualTo(2);
        assertThat(scores.path(0).path("id").asString()).isEqualTo("j1");
        assertThat(scores.path(0).path("score").asInt()).isEqualTo(80);
        assertThat(scores.path(1).path("id").asString()).isEqualTo("j2");
        assertThat(scores.path(1).path("score").asInt()).isEqualTo(40);
    }

    @Test
    void countsTheCallsAndKeepsThePromptUntilReset() throws Exception {
        assertThat(scores("Jobs:\nj1 | Java Dev | java").isEmpty()).as("no scores set").isTrue();
        scores(PROMPT);

        assertThat(stub.callCount()).isEqualTo(2);
        assertThat(stub.lastPrompt()).isEqualTo(PROMPT);
        stub.reset();
        assertThat(stub.callCount()).isZero();
        assertThat(stub.lastPrompt()).isNull();
    }

    private JsonNode scores(String prompt) throws Exception {
        String body = json.writeValueAsString(Map.of("messages", List.of(Map.of("role", "user", "content", prompt))));
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(stub.baseUrl() + "/chat/completions")).timeout(Duration.ofSeconds(2))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(json.readTree(response.body()).path("choices").path(0).path("message").path("content").asString());
    }
}
