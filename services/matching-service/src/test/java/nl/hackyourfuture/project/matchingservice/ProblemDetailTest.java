package nl.hackyourfuture.project.matchingservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copied {@code GlobalExceptionHandler} answers a {@code ResponseStatusException} with its reason
 * as the problem's detail. Without it the reason is lost: {@code server.error.include-message} is
 * {@code never}, and top-matches' 422 says what to do next in that reason.
 */
@Import(ProblemDetailTest.Thrower.class)
class ProblemDetailTest extends MatchingServiceTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final TestIdentity IDENTITY = TestIdentity.instance();

    @LocalServerPort
    private int port;

    @Test
    void aResponseStatusReasonIsTheProblemDetail() throws IOException, InterruptedException {
        String userToken = IDENTITY.token(UUID.randomUUID());
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/test/incomplete-profile"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Cookie", "access_token=" + userToken)
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(422);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode problem = mapper.readTree(response.body());
        assertThat(problem.path("detail").asString()).isEqualTo("Complete your profile");
    }

    @RestController
    static class Thrower {

        @PostMapping("/test/incomplete-profile")
        void incompleteProfile() {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Complete your profile");
        }
    }
}
