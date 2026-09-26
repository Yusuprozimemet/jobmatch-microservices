package nl.hackyourfuture.project.backend.shared.applications;

import java.util.Collection;
import java.util.Map;

/**
 * How many users saved each posting.
 *
 * <p>{@code jobs} shows the number on every posting, and used to compute it with a subquery
 * into {@code saved_jobs}, which is {@code applications}' table. Now it asks here instead.
 * Not temporary, unlike {@code shared.identity}'s two: this is the call that goes over HTTP
 * when application-service is extracted, so it is shaped for that - one call per page of
 * postings, never one per posting.
 *
 * <p>job-service's copy (Day 17): its image is built from its own folder and sees nothing of
 * {@code backend/}. The monolith's copy is the other half of the same contract; the tests that
 * cross into job-service keep the two in step.
 */
public interface SavedJobCounts {

    /**
     * One entry per distinct id asked for, including the ones nobody saved, which map to 0.
     * An empty collection gets an empty map.
     */
    Map<String, Integer> countsFor(Collection<String> postingIds);
}
