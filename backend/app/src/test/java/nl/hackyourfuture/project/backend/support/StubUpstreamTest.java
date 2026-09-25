package nl.hackyourfuture.project.backend.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The stub the internal clients fail against does what its failure tests will rely on. */
class StubUpstreamTest {

    private final StubUpstream stub = StubUpstream.instance();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetTheStub() {
        stub.reset();
    }

    @Test
    void answersRefusesAndDoesNotKnowTheRest() throws Exception {
        stub.answer("/answer", 200, "{\"ok\":true}");
        stub.refuse("/refuse", 503);

        assertThat(get("/answer").body()).isEqualTo("{\"ok\":true}");
        assertThat(get("/refuse").statusCode()).isEqualTo(503);
        assertThat(get("/unknown").statusCode()).isEqualTo(404);
    }

    @Test
    void aHangHoldsUpNoOtherRequestAndEndsOnReset() throws Exception {
        stub.hang("/hang");
        stub.answer("/answer", 200, "{}");
        // A POST, as the internal clients send: the JDK client retries a GET once when the
        // connection closes with no answer, and the retry would arrive after the reset.
        CompletableFuture<HttpResponse<String>> hung = http.sendAsync(
                HttpRequest.newBuilder(URI.create(stub.baseUrl() + "/hang")).timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (stub.calls("/hang") == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(stub.calls("/hang")).as("the hung request has arrived").isOne();

        assertThat(get("/answer").statusCode()).isEqualTo(200);
        assertThat(hung).isNotDone();

        stub.reset();
        // get, not failsWithin: failsWithin also passes on a timeout, a hang that never ended.
        assertThatThrownBy(() -> hung.get(1, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void countsTheCallsEachPathGetsUntilReset() throws Exception {
        stub.refuse("/counted", 503);
        get("/counted");
        get("/counted");

        assertThat(stub.calls("/counted")).isEqualTo(2);
        assertThat(stub.calls("/other")).isZero();
        stub.reset();
        assertThat(stub.calls("/counted")).isZero();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(stub.baseUrl() + path)).timeout(Duration.ofSeconds(2)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
