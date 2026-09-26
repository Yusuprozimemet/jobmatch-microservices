package nl.hackyourfuture.project.jobservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The job-service's public access rules. The GET routes are permitted but answer 404 today
 * (nothing serves them yet); Track D's controllers turn these into 200s. Anything else answers
 * 401 because there is no token.
 */
class PublicAccessTest extends JobServiceTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void getJobsAnswers404Anonymous() throws Exception {
        assertThat(get("/api/jobs").statusCode()).isEqualTo(404);
    }

    @Test
    void getJobsFiltersAnswers404Anonymous() throws Exception {
        assertThat(get("/api/jobs/filters").statusCode()).isEqualTo(404);
    }

    @Test
    void getSingleJobAnswers404Anonymous() throws Exception {
        assertThat(get("/api/jobs/seed-0001").statusCode()).isEqualTo(404);
    }

    @Test
    void getServiceKeySetAnswers404Anonymous() throws Exception {
        assertThat(get("/.well-known/service-jwks.json").statusCode()).isEqualTo(404);
    }

    @Test
    void getJobsWithGarbageCookieAnswers404NotUnauthorized() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/jobs"))
                .GET()
                .header("Cookie", "access_token=garbage")
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        // The cookie is not read: it is ignored, not rejected. The route is permitted, so 404 not 401.
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void postJobsAnswers401() throws Exception {
        assertThat(post("/api/jobs", "").statusCode()).isEqualTo(401);
    }

    @Test
    void getSavedJobsAnswers401() throws Exception {
        assertThat(get("/api/saved-jobs").statusCode()).isEqualTo(401);
    }

    @Test
    void getJobExtraAnswers401() throws Exception {
        assertThat(get("/api/jobs/seed-0001/extra").statusCode()).isEqualTo(401);
    }

    @Test
    void postInternalPostingsBatchAnswers401() throws Exception {
        assertThat(post("/internal/postings/batch", "").statusCode()).isEqualTo(401);
    }

    @Test
    void getActuatorHealthOnPublicPortAnswers401() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
