package nl.hackyourfuture.project.backend.matching;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import nl.hackyourfuture.project.backend.shared.internal.InternalClients;
import nl.hackyourfuture.project.backend.shared.jobs.PostingShortlist;
import nl.hackyourfuture.project.backend.shared.jobs.ShortlistedPosting;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code PostingShortlist} top matches uses (Day 19): {@code jobs}'
 * {@code /internal/postings/shortlist} over HTTP, in this process until Day 17 moves {@code jobs} out. {@code @Primary}, so it serves;
 * the route itself still answers from {@code JobsDirectory}.
 *
 * <p>An outage (a connection or timeout error, a 5xx, the breaker open) throws a
 * {@code ResponseStatusException} with 503: matching without postings is meaningless, and the
 * answer is never an empty list, which would be indistinguishable from "no matches found". A 4xx is
 * a bug on this side, not an outage, so it is not caught and the caller answers 500.
 */
@Component
@Primary
class PostingShortlistClient implements PostingShortlist {

    private static final ParameterizedTypeReference<List<ShortlistedPosting>> POSTINGS =
            new ParameterizedTypeReference<>() { };

    private final Supplier<RestClient> jobs;

    PostingShortlistClient(InternalClients internalClients) {
        this.jobs = internalClients.forUrlProperty("app.internal.jobs-url");
    }

    @Override
    @CircuitBreaker(name = "postingShortlist", fallbackMethod = "unavailable")
    public List<ShortlistedPosting> shortlist(String city, List<String> skills, int limit) {
        Map<String, Object> body = new HashMap<>();
        body.put("city", city);
        body.put("skills", skills);
        body.put("limit", limit);
        List<ShortlistedPosting> answer = jobs.get().post()
                .uri("/internal/postings/shortlist")
                .body(body)
                .retrieve()
                .body(POSTINGS);
        return answer != null ? answer : List.of();
    }

    // Chosen by the exception's type; anything else, a 4xx among it, is rethrown.
    List<ShortlistedPosting> unavailable(String city, List<String> skills, int limit, ResourceAccessException e) {
        throw postingsUnreachable();
    }

    List<ShortlistedPosting> unavailable(String city, List<String> skills, int limit, HttpServerErrorException e) {
        throw postingsUnreachable();
    }

    List<ShortlistedPosting> unavailable(String city, List<String> skills, int limit, CallNotPermittedException e) {
        throw postingsUnreachable();
    }

    private static ResponseStatusException postingsUnreachable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "The postings could not be reached; matching is temporarily unavailable. Try again shortly.");
    }
}
