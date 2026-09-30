package nl.hackyourfuture.project.matchingservice;

import nl.hackyourfuture.project.backend.matching.MatchScorer;
import nl.hackyourfuture.project.backend.shared.jobs.ShortlistedPosting;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bulkhead on the model call holds ten scoring calls at most, and an eleventh is answered at
 * once without the model (Day 21). The harness's {@code HungModelIT} checks the same through
 * top-matches; this one fails in the service's own build, without the container.
 */
class BulkheadHungModelTest extends MatchingServiceTest {

    private static final int IN_FLIGHT = 10;

    @Autowired
    private MatchScorer matchScorer;

    @Test
    void anEleventhScoringCallIsAnsweredWithoutTheModelWhileTenWaitOnIt() throws Exception {
        List<String> skills = List.of("java", "sql");
        List<ShortlistedPosting> jobs = new ArrayList<>();
        for (int i = 0; i < IN_FLIGHT; i++) {
            jobs.add(new ShortlistedPosting("bulkhead-" + i, "Bulkhead Engineer " + i, "Company " + i,
                    "testville", "Engineering", LocalDate.now(), skills, skills, 2));
        }
        model().reset();
        model().hang();

        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Map<String, MatchScorer.Score>>> held = new ArrayList<>();
            for (int i = 0; i < IN_FLIGHT; i++) {
                held.add(CompletableFuture.supplyAsync(() -> matchScorer.score(skills, jobs), threads));
            }
            try {
                model().awaitHanging(IN_FLIGHT);

                long started = System.nanoTime();
                Map<String, MatchScorer.Score> eleventh = matchScorer.score(skills, jobs);
                Duration took = Duration.ofNanos(System.nanoTime() - started);

                assertThat(eleventh).as("the eleventh call, in skill-overlap order").isEmpty();
                assertThat(took).as("the eleventh call with ten held by the model")
                        .isLessThan(Duration.ofSeconds(1));
                assertThat(model().callCount()).as("calls that reached the model").isEqualTo(IN_FLIGHT);
            } finally {
                model().reset();
            }
            for (CompletableFuture<Map<String, MatchScorer.Score>> call : held) {
                call.get(30, TimeUnit.SECONDS);
            }
        }
    }
}
