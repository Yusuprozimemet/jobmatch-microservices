package nl.hackyourfuture.project.backend.applications;

import nl.hackyourfuture.project.backend.applications.dto.SavedJobResponse;
import nl.hackyourfuture.project.backend.applications.SavedJobRepository.SavedRow;
import nl.hackyourfuture.project.backend.shared.identity.UserDirectory;
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
    private final UserDirectory userDirectory;
    private final PostingLookup postingLookup;

    public SavedJobService(SavedJobRepository savedJobRepository, UserDirectory userDirectory,
                           PostingLookup postingLookup) {
        this.savedJobRepository = savedJobRepository;
        this.userDirectory = userDirectory;
        this.postingLookup = postingLookup;
    }

    // Resolves user email to UUID or fails if not found. Asks identity rather than reading
    // its table: this module no longer knows that users have a table at all.
    private UUID getUserIdByEmail(String email) {
        return userDirectory.findUserIdByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    // Save a new job for a user if not already saved
    public void saveJobByEmail(String email, String postingId) {
        UUID userId = getUserIdByEmail(email);

        if (savedJobRepository.isJobSaved(userId, postingId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is already saved");
        }

        savedJobRepository.saveJob(userId, postingId);
    }

    // Get paginated saved jobs with details for a user. The sort key, posted_date, belongs to
    // jobs, so the whole list is hydrated in one call and the page is cut here, never in SQL.
    // Totals therefore come from saved_jobs alone, whatever the mart has lost. A personal
    // tracker is tens of rows; revisit if a list is ever long enough to measure.
    public PageResponse<SavedJobResponse> getSavedJobsByEmail(String email, int page, int size) {
        UUID userId = getUserIdByEmail(email);
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
    public boolean updateJobStateByEmail(String email, String postingId, JobState newState) {
        UUID userId = getUserIdByEmail(email);
        return savedJobRepository.updateJobState(userId, postingId, newState);
    }

    // Remove a saved job
    public boolean removeSavedJobByEmail(String email, String postingId) {
        UUID userId = getUserIdByEmail(email);
        return savedJobRepository.removeSavedJob(userId, postingId);
    }

    // Get statistics of saved jobs grouped by state
    public Map<JobState, Integer> getJobStatsByEmail(String email) {
        UUID userId = getUserIdByEmail(email);
        return savedJobRepository.getJobStats(userId);
    }
}