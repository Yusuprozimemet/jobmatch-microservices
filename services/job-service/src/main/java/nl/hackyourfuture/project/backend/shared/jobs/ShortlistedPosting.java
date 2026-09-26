package nl.hackyourfuture.project.backend.shared.jobs;

import java.time.LocalDate;
import java.util.List;

/**
 * One posting on the match shortlist, before the model scores it.
 *
 * <p>Was {@code JobMatchRepository.JobMatchRow} in {@code matching}; moved here unchanged when
 * the query that produces it moved into {@code jobs}.
 *
 * <p>job-service's copy (Day 17): its image is built from its own folder and sees nothing of
 * {@code backend/}. The monolith's copy is the other half of the same contract; the tests that
 * cross into job-service keep the two in step.
 */
public record ShortlistedPosting(
        String postingId,
        String title,
        String company,
        String location,
        String category,
        LocalDate postedDate,
        List<String> jobSkills,
        List<String> matchedSkills,
        int jobSkillCount
) {

    public int matchedCount() {
        return matchedSkills.size();
    }
}
