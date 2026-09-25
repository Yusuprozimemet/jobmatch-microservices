package nl.hackyourfuture.project.backend.jobs;

import nl.hackyourfuture.project.backend.shared.jobs.PostingLookup;
import nl.hackyourfuture.project.backend.shared.jobs.PostingSummary;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@code jobs} answers other services over HTTP (Day 18): the calls other modules make in
 * process today, for Day 19's clients. Behind the {@code /internal/**} chain, so service tokens
 * only (Day 39); the gateway does not route it and the public OpenAPI does not list it.
 */
@RestController
class InternalPostingController {

    /** Distinct ids per batch. Saved jobs asks for a whole list, so Day 19's client splits at this. */
    static final int MAX_IDS = 500;

    private final PostingLookup postingLookup;

    InternalPostingController(PostingLookup postingLookup) {
        this.postingLookup = postingLookup;
    }

    /**
     * {@link PostingLookup#byIds} as it is: an id the mart does not have is absent, and an empty
     * list gets {@code {}} without a query. Refused before any query when it is too long, so an
     * oversized batch costs a 400, not a slow {@code IN} list.
     */
    @PostMapping("/internal/postings/batch")
    Map<String, PostingSummary> batch(@RequestBody IdsRequest body) {
        if (body == null || body.ids() == null || body.ids().contains(null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids is required, without nulls");
        }
        Set<String> ids = new LinkedHashSet<>(body.ids());
        if (ids.size() > MAX_IDS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most " + MAX_IDS + " distinct ids");
        }
        return postingLookup.byIds(ids);
    }

    record IdsRequest(List<String> ids) {
    }
}
