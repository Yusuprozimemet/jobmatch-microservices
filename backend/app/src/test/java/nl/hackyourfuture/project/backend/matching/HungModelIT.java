package nl.hackyourfuture.project.backend.matching;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.MatchingTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model that never answers costs top-matches its read timeout and nothing else (Day 21). Job
 * search has been isolated from it since Day 17, in its own container; the rest of the monolith
 * by virtual threads, which a request waiting on the model does not hold up. Both hold today,
 * and must still hold once matching runs in its own container.
 */
class HungModelIT extends MatchingTest {

    private static final int IN_FLIGHT = 10;

    @Test
    void theRestOfTheApplicationAnswersWhileTheModelHangs() throws Exception {
        TestUser reader = userWithProfile();
        List<ApiClient> askers = new ArrayList<>();
        for (int i = 0; i < IN_FLIGHT; i++) {
            askers.add(authenticatedAs(userWithProfile()));
            posting("hung-" + UUID.randomUUID().toString().substring(0, 8), "Hung Engineer " + i, "java", "sql");
        }
        model().hang();

        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ApiResponse>> topMatches = askers.stream()
                    .map(asker -> CompletableFuture.supplyAsync(() -> asker.get("/api/jobs/top-matches"), threads))
                    .toList();
            model().awaitHanging(IN_FLIGHT);

            assertAnswersWithinASecond("GET /api/jobs, job-service", () -> anonymous().get("/api/jobs"));
            assertAnswersWithinASecond("GET /api/profile, the monolith", () -> authenticatedAs(reader).get("/api/profile"));

            model().reset();
            for (CompletableFuture<ApiResponse> request : topMatches) {
                assertThat(request.get(30, TimeUnit.SECONDS).status()).as("top-matches once released").isEqualTo(200);
            }
        }
    }

    @Test
    void topMatchesAnswersWithinTheGatewaysReadWhileTheModelHangs() {
        TestUser user = userWithProfile();
        posting("hung-read-1", "Hung Engineer", "java", "sql");
        model().hang();

        long started = System.nanoTime();
        ApiResponse response = authenticatedAs(user).get("/api/jobs/top-matches");
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.status()).isEqualTo(200);
        assertThat(took).as("top-matches with the model hung, against the gateway's 30 s read")
                .isLessThan(Duration.ofSeconds(30));
        assertThat(response.json()).isNotEmpty()
                .allSatisfy(match -> assertThat(match.get("aiScored").asBoolean()).isFalse());
    }

    private static void assertAnswersWithinASecond(String route, Supplier<ApiResponse> request) {
        long started = System.nanoTime();
        ApiResponse response = request.get();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.status()).as(route).isEqualTo(200);
        assertThat(took).as(route + " with " + IN_FLIGHT + " top-matches held by the model")
                .isLessThan(Duration.ofSeconds(1));
    }
}
