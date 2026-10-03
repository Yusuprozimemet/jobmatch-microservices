package nl.hackyourfuture.project.applicationservice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * {@code never}, and a deleted user's 404 says "User not found" in that reason
 * ({@code SessionWithoutAUserIT}). A request body that fails {@code @Valid} answers 400 with each
 * field's message, as the saved-jobs requests' {@code @NotBlank} and {@code @NotNull} do today.
 */
@Import(ProblemDetailTest.Thrower.class)
class ProblemDetailTest extends ApplicationServiceTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final TestIdentity IDENTITY = TestIdentity.instance();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void aResponseStatusReasonIsTheProblemDetail() throws IOException, InterruptedException {
        String userToken = IDENTITY.token(UUID.randomUUID());
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/test/user-not-found"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Cookie", "access_token=" + userToken)
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        JsonNode problem = MAPPER.readTree(response.body());
        assertThat(problem.path("detail").asString()).isEqualTo("User not found");
    }

    @Test
    void aValidationErrorIs400WithTitle() throws IOException, InterruptedException {
        String userToken = IDENTITY.token(UUID.randomUUID());
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/test/validation-error"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Cookie", "access_token=" + userToken)
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode problem = MAPPER.readTree(response.body());
        assertThat(problem.path("title").asString()).isEqualTo("Validation failed");
        assertThat(problem.path("errors").path("field").asString()).isEqualTo("Field is required");
    }

    @RestController
    static class Thrower {

        @PostMapping("/test/user-not-found")
        void userNotFound() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

        @PostMapping("/test/validation-error")
        void validationError(@Valid @RequestBody TestRequest request) {
        }
    }

    record TestRequest(@NotBlank(message = "Field is required") String field) {
    }
}
