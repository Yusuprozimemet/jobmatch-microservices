package nl.hackyourfuture.project.backend.shared.jobs;

import java.util.List;

/**
 * The postings worth scoring for a set of skills, best first.
 *
 * <p>{@code matching} used to build this list with its own query over the mart, which is
 * {@code jobs}' data. Now {@code jobs} runs that query and {@code matching} only scores what
 * comes back. Day 18 puts the same call behind {@code POST /internal/postings/shortlist}.
 *
 * <p>job-service's copy (Day 17): its image is built from its own folder and sees nothing of
 * {@code backend/}. The monolith's copy is the other half of the same contract; the tests that
 * cross into job-service keep the two in step.
 */
public interface PostingShortlist {

    /**
     * Open postings with at least one listed skill, ranked by how many of {@code skills} they
     * match and then by newest, one per title and company, at most {@code limit} of them.
     *
     * @param city   optional; when given, only postings in that city
     * @param skills lowercase, non-empty
     */
    List<ShortlistedPosting> shortlist(String city, List<String> skills, int limit);
}
