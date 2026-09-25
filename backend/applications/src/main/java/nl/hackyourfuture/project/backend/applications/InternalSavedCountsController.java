package nl.hackyourfuture.project.backend.applications;

import nl.hackyourfuture.project.backend.shared.applications.SavedJobCounts;
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
 * What {@code applications} answers other services over HTTP (Day 18): saved counts by posting,
 * the call job search needs once {@code jobs} leaves on Day 17 (Day 19's client calls it). Behind
 * the {@code /internal/**} chain, so service tokens only (Day 39); the gateway does not route it
 * and the public OpenAPI does not list it.
 */
@RestController
class InternalSavedCountsController {

    /** Distinct ids per request. Job search asks one page at a time, at most 100. */
    static final int MAX_IDS = 500;

    private final SavedJobCounts savedJobCounts;

    InternalSavedCountsController(SavedJobCounts savedJobCounts) {
        this.savedJobCounts = savedJobCounts;
    }

    /**
     * {@link SavedJobCounts#countsFor} as it is: one entry per distinct id, 0 for ids nobody
     * saved, and an empty list gets {@code {}} without a query. Refused before any query when it
     * is too long, so an oversized request costs a 400, not a slow query.
     */
    @PostMapping("/internal/saved-counts")
    Map<String, Integer> savedCounts(@RequestBody IdsRequest body) {
        if (body == null || body.ids() == null || body.ids().contains(null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids is required, without nulls");
        }
        Set<String> ids = new LinkedHashSet<>(body.ids());
        if (ids.size() > MAX_IDS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most " + MAX_IDS + " distinct ids");
        }
        return savedJobCounts.countsFor(ids);
    }

    record IdsRequest(List<String> ids) {
    }
}
