package nl.hackyourfuture.project.backend.applications;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import nl.hackyourfuture.project.backend.shared.internal.InternalClients;
import nl.hackyourfuture.project.backend.shared.jobs.PostingLookup;
import nl.hackyourfuture.project.backend.shared.jobs.PostingSummary;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code PostingLookup} saved jobs uses (Day 19): {@code jobs}' {@code /internal/postings/batch}
 * over HTTP, in this process until Day 17 moves {@code jobs} out. {@code @Primary}, so it serves;
 * the route itself still answers from {@code JobsDirectory}.
 *
 * <p>In chunks of {@value #CHUNK} distinct ids, one after another: the route refuses more, and saved
 * jobs asks for a user's whole list in one call, which nothing keeps under the cap.
 *
 * <p>An outage (a connection or timeout error, a 5xx, the breaker open) gets {@code {}} for the
 * whole call, even when a chunk had answered: saved jobs then lists every row with empty details,
 * the shape of a posting that has left the mart (Day 04), and not newest first, since the date is
 * the posting's. A 4xx is a bug on this side, not an outage, so it is not caught and the caller
 * answers 500.
 */
@Component
@Primary
class PostingLookupClient implements PostingLookup {

    /** The batch route's cap, {@code InternalPostingController.MAX_IDS} (Day 18). */
    static final int CHUNK = 500;

    private static final ParameterizedTypeReference<Map<String, PostingSummary>> SUMMARIES =
            new ParameterizedTypeReference<>() { };

    private final Supplier<RestClient> jobs;

    PostingLookupClient(InternalClients internalClients) {
        this.jobs = internalClients.forUrlProperty("app.internal.jobs-url");
    }

    @Override
    @CircuitBreaker(name = "postingLookup", fallbackMethod = "unavailable")
    public Map<String, PostingSummary> byIds(Collection<String> postingIds) {
        List<String> ids = List.copyOf(new LinkedHashSet<>(postingIds));
        Map<String, PostingSummary> summaries = new LinkedHashMap<>();
        for (List<String> chunk : chunks(ids)) {
            Map<String, PostingSummary> answer = jobs.get().post()
                    .uri("/internal/postings/batch")
                    .body(Map.of("ids", chunk))
                    .retrieve()
                    .body(SUMMARIES);
            if (answer != null) {
                summaries.putAll(answer);
            }
        }
        return summaries;
    }

    // Chosen by the exception's type; anything else, a 4xx among it, is rethrown.
    Map<String, PostingSummary> unavailable(Collection<String> postingIds, ResourceAccessException e) {
        return Map.of();
    }

    Map<String, PostingSummary> unavailable(Collection<String> postingIds, HttpServerErrorException e) {
        return Map.of();
    }

    Map<String, PostingSummary> unavailable(Collection<String> postingIds, CallNotPermittedException e) {
        return Map.of();
    }

    private static List<List<String>> chunks(List<String> ids) {
        List<List<String>> chunks = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            chunks.add(ids.subList(from, Math.min(from + CHUNK, ids.size())));
        }
        return chunks;
    }
}
