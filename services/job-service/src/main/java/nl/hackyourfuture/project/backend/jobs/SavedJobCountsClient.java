package nl.hackyourfuture.project.backend.jobs;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import nl.hackyourfuture.project.backend.shared.applications.SavedJobCounts;
import nl.hackyourfuture.project.backend.shared.internal.InternalClients;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code SavedJobCounts} job search and job detail use (Day 19): {@code applications}'
 * {@code /internal/saved-counts} over HTTP, in this process until Day 17 moves {@code jobs} out.
 * {@code @Primary}, so it serves; the route itself still answers from {@code ApplicationsDirectory}.
 *
 * <p>No chunking: its callers ask one page at a time, at most 100 postings ({@code JobController}
 * caps the page), under the route's 500.
 *
 * <p>An outage (a connection or timeout error, a 5xx, the breaker open) returns every distinct
 * requested id mapped to 0. {@code JobService} passes {@code counts.get(id)} to
 * {@code withSavedCount(int)}, so a missing entry is a {@code NullPointerException}. A 4xx is a
 * bug on this side, not an outage, so it is not caught and the caller answers 500.
 */
@Component
@Primary
class SavedJobCountsClient implements SavedJobCounts {

    private static final ParameterizedTypeReference<Map<String, Integer>> COUNTS =
            new ParameterizedTypeReference<>() { };

    private final Supplier<RestClient> applications;

    SavedJobCountsClient(InternalClients internalClients) {
        this.applications = internalClients.forUrlProperty("app.internal.applications-url");
    }

    @Override
    @CircuitBreaker(name = "savedJobCounts", fallbackMethod = "unavailable")
    public Map<String, Integer> countsFor(Collection<String> postingIds) {
        if (postingIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = List.copyOf(new LinkedHashSet<>(postingIds));
        Map<String, Integer> answer = applications.get().post()
                .uri("/internal/saved-counts")
                .body(Map.of("ids", ids))
                .retrieve()
                .body(COUNTS);
        return answer != null ? answer : Map.of();
    }

    // Chosen by the exception's type; anything else, a 4xx among it, is rethrown.
    Map<String, Integer> unavailable(Collection<String> postingIds, ResourceAccessException e) {
        return allZero(postingIds);
    }

    Map<String, Integer> unavailable(Collection<String> postingIds, HttpServerErrorException e) {
        return allZero(postingIds);
    }

    Map<String, Integer> unavailable(Collection<String> postingIds, CallNotPermittedException e) {
        return allZero(postingIds);
    }

    private static Map<String, Integer> allZero(Collection<String> postingIds) {
        LinkedHashMap<String, Integer> zeros = new LinkedHashMap<>();
        for (String id : new LinkedHashSet<>(postingIds)) {
            zeros.put(id, 0);
        }
        return zeros;
    }
}
