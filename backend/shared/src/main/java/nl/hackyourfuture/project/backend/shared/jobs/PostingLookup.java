package nl.hackyourfuture.project.backend.shared.jobs;

import java.util.Collection;
import java.util.Map;

/**
 * Posting details by id, for a module that holds ids and needs to show what they point at.
 *
 * <p>{@code applications} keeps saved jobs as bare posting ids, and used to fill in their
 * details with a {@code LEFT JOIN} into {@code analytics.fct_postings}, which is {@code jobs}'
 * data. Now it asks here. Shaped for the network it will cross in Phase 3: one call for a whole
 * list, never one per posting.
 */
public interface PostingLookup {

    /**
     * One entry per id the mart has. An id it does not have - a posting that has left the mart
     * since it was saved - is <strong>absent</strong>, not mapped to an empty summary, and the
     * caller decides what that means. An empty collection gets an empty map.
     */
    Map<String, PostingSummary> byIds(Collection<String> postingIds);
}
