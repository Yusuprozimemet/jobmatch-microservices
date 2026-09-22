package nl.hackyourfuture.project.backend.applications;

import nl.hackyourfuture.project.backend.applications.dto.SavedJobResponse;
import nl.hackyourfuture.project.backend.shared.identity.UserDirectory;
import nl.hackyourfuture.project.backend.shared.dto.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@Service
public class SavedJobService {

    private final SavedJobRepository savedJobRepository;
    private final UserDirectory userDirectory;

    public SavedJobService(SavedJobRepository savedJobRepository, UserDirectory userDirectory) {
        this.savedJobRepository = savedJobRepository;
        this.userDirectory = userDirectory;
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

    // Get paginated saved jobs with details for a user
        public PageResponse<SavedJobResponse> getSavedJobsByEmail(String email, int page, int size) {
            UUID userId = getUserIdByEmail(email);
            return savedJobRepository.getSavedJobsWithDetails(userId, page, size);
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