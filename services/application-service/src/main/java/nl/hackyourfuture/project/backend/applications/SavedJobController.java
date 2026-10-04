package nl.hackyourfuture.project.backend.applications;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nl.hackyourfuture.project.backend.applications.dto.SaveJobRequest;
import nl.hackyourfuture.project.backend.applications.dto.SavedJobResponse;
import nl.hackyourfuture.project.backend.applications.dto.UpdateJobStateRequest;
import nl.hackyourfuture.project.backend.shared.dto.PageResponse;
import nl.hackyourfuture.project.backend.shared.web.CurrentUserId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/saved-jobs")
@Tag(name = "Saved Jobs", description = "Endpoints for managing and tracking saved jobs")
public class SavedJobController {

    private final SavedJobService savedJobService;

    public SavedJobController(SavedJobService savedJobService) {
        this.savedJobService = savedJobService;
    }

    // Save a job for the authenticated user
    @PostMapping
    @Operation(summary = "Save a job posting")
    @ApiResponse(responseCode = "201", description = "Job successfully saved")
    public ResponseEntity<Void> saveJob(
            @CurrentUserId Optional<UUID> userId,
            @Valid @RequestBody SaveJobRequest request
    ) {
        savedJobService.saveJob(requireUser(userId), request.postingId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // Move a saved job to another state
    @PatchMapping("/{postingId}")
    @Operation(summary = "Update saved job state")
    @ApiResponse(responseCode = "200", description = "Job state updated successfully")
    @ApiResponse(responseCode = "404", description = "Saved job not found")
    public ResponseEntity<Void> updateJobState(
            @CurrentUserId Optional<UUID> userId,
            @PathVariable String postingId,
            @Valid @RequestBody UpdateJobStateRequest request
    ) {
        boolean updated = savedJobService.updateJobState(requireUser(userId), postingId, request.newState());
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().build();
    }

    // Unsave a job by removing it from the user's list
    @DeleteMapping("/{postingId}")
    @Operation(summary = "Remove a saved job")
    @ApiResponse(responseCode = "204", description = "Saved job removed successfully")
    @ApiResponse(responseCode = "404", description = "Saved job not found")
    public ResponseEntity<Void> removeSavedJob(
            @CurrentUserId Optional<UUID> userId,
            @PathVariable String postingId
    ) {
        boolean removed = savedJobService.removeSavedJob(requireUser(userId), postingId);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    // Count how many jobs sit in each stage for the dashboard
    @GetMapping("/stats")
    @Operation(summary = "Get saved job statistics by state")
    @ApiResponse(responseCode = "200", description = "Statistics retrieved successfully")
    public ResponseEntity<Map<JobState, Integer>> getJobStats(
            @CurrentUserId Optional<UUID> userId
    ) {
        Map<JobState, Integer> stats = savedJobService.getJobStats(requireUser(userId));
        return ResponseEntity.ok(stats);
    }

    // List all saved jobs for the authenticated user
    @GetMapping
    @Operation(summary = "List user's saved jobs")
    @ApiResponse(responseCode = "200", description = "Saved jobs retrieved successfully")
    public ResponseEntity<PageResponse<SavedJobResponse>> getSavedJobs(
            @CurrentUserId Optional<UUID> userId,
            @RequestParam(defaultValue = "0") int page, // Default page index 0
            @RequestParam(defaultValue = "20") int size  // Default page size 20
    ) {
        // Validate page index (must be >= 0)
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page index must be greater than or equal to 0");
        }

        // Validate page size (must be >= 1)
        if (size < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size must be greater than 0");
        }

        // Cap maximum page size to prevent large database loads
        int cappedSize = Math.min(size, 100);
        PageResponse<SavedJobResponse> savedJobs = savedJobService.getSavedJobs(requireUser(userId), page, cappedSize);
        return ResponseEntity.ok(savedJobs);
    }

    // A session whose account is gone: this module has always called that a missing user.
    private static UUID requireUser(Optional<UUID> userId) {
        return userId.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
