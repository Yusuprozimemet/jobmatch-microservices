package nl.hackyourfuture.project.backend.savedjobs;

import nl.hackyourfuture.project.backend.savedjobs.dto.SavedJobResponse;
import nl.hackyourfuture.project.backend.user.UserRepository;
import nl.hackyourfuture.project.backend.jobs.dto.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@Service
public class SavedJobService {

    private final SavedJobRepository savedJobRepository;
    private final UserRepository userRepository;

    public SavedJobService(SavedJobRepository savedJobRepository, UserRepository userRepository) {
        this.savedJobRepository = savedJobRepository;
        this.userRepository = userRepository;
    }

    // Resolves user email to UUID or fails if not found
    private UUID getUserIdByEmail(String email) {
        return userRepository.getUserByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"))
                .getId();
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