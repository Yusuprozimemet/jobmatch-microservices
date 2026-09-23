package nl.hackyourfuture.project.backend.applications;

import nl.hackyourfuture.project.backend.applications.dto.SavedJobResponse;
import nl.hackyourfuture.project.backend.applications.SavedJobRepository.SavedRow;
import nl.hackyourfuture.project.backend.shared.dto.PageResponse;
import nl.hackyourfuture.project.backend.shared.jobs.PostingLookup;
import nl.hackyourfuture.project.backend.shared.jobs.PostingSummary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SavedJobService {

    // Newest posting first, postings that have left the mart last, posting id breaking ties:
    // the order the LEFT JOIN into the mart used to give. The tie-break moved from Postgres's
    // collation to String.compareTo; they agree because posting ids are md5 hex (int_postings).
    private static final Comparator<SavedJobResponse> NEWEST_POSTING_FIRST = Comparator
            .comparing(SavedJobResponse::postedDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(SavedJobResponse::postingId);

    private final SavedJobRepository savedJobRepository;
    private final PostingLookup postingLookup;

    public SavedJobService(SavedJobRepository savedJobRepository, PostingLookup postingLookup) {
        this.savedJobRepository = savedJobRepository;
        this.postingLookup = postingLookup;
    }

    // Save a new job for a user if not already saved
    public void saveJob(UUID userId, String postingId) {
        if (savedJobRepository.isJobSaved(userId, postingId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is already saved");
        }

        savedJobRepository.saveJob(userId, postingId);
    }

    // Get paginated saved jobs with details for a user. The sort key, posted_date, belongs to
    // jobs, so the whole list is hydrated in one call and the page is cut here, never in SQL.
    // Totals therefore come from saved_jobs alone, whatever the mart has lost. A personal
    // tracker is tens of rows; revisit if a list is ever long enough to measure.
    public PageResponse<SavedJobResponse> getSavedJobs(UUID userId, int page, int size) {
        List<SavedRow> rows = savedJobRepository.findSavedJobs(userId);
        Map<String, PostingSummary> postings = postingLookup.byIds(
                rows.stream().map(SavedRow::postingId).toList());
        List<SavedJobResponse> content = rows.stream()
                .map(row -> toResponse(row, postings.get(row.postingId())))
                .sorted(NEWEST_POSTING_FIRST)
                .skip((long) page * size)
                .limit(size)
                .toList();
        return PageResponse.of(content, page, size, rows.size());
    }

    // A posting that has left the mart keeps its row, with every detail null and no skills.
    private static SavedJobResponse toResponse(SavedRow row, PostingSummary posting) {
        if (posting == null) {
            return new SavedJobResponse(row.postingId(), row.jobState(), null, null, null, null, null,
                    List.of(), null, null, null, null, null, null);
        }
        return new SavedJobResponse(row.postingId(), row.jobState(), posting.title(), posting.companyName(),
                posting.location(), posting.workMode(), posting.isRemote(), posting.skills(),
                posting.employmentType(), posting.postedDate(), posting.source(), posting.category(),
                posting.freshnessClass(), posting.ageDays());
    }

    // Update the state of a saved job
    public boolean updateJobState(UUID userId, String postingId, JobState newState) {
        return savedJobRepository.updateJobState(userId, postingId, newState);
    }

    // Remove a saved job
    public boolean removeSavedJob(UUID userId, String postingId) {
        return savedJobRepository.removeSavedJob(userId, postingId);
    }

    // Get statistics of saved jobs grouped by state
    public Map<JobState, Integer> getJobStats(UUID userId) {
        return savedJobRepository.getJobStats(userId);
    }
}