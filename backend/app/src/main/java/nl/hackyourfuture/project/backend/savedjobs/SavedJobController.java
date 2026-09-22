package nl.hackyourfuture.project.backend.savedjobs;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nl.hackyourfuture.project.backend.savedjobs.dto.SaveJobRequest;
import nl.hackyourfuture.project.backend.savedjobs.dto.SavedJobResponse;
import nl.hackyourfuture.project.backend.savedjobs.dto.UpdateJobStateRequest;
import nl.hackyourfuture.project.backend.jobs.dto.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

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
            @AuthenticationPrincipal Object principal,
            @Valid @RequestBody SaveJobRequest request
    ) {
        String email = getCurrentUserEmail(principal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in"));
        savedJobService.saveJobByEmail(email, request.postingId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // Move a saved job to another state
    @PatchMapping("/{postingId}")
    @Operation(summary = "Update saved job state")
    @ApiResponse(responseCode = "200", description = "Job state updated successfully")
    @ApiResponse(responseCode = "404", description = "Saved job not found")
    public ResponseEntity<Void> updateJobState(
            @AuthenticationPrincipal Object principal,
            @PathVariable String postingId,
            @Valid @RequestBody UpdateJobStateRequest request
    ) {
        String email = getCurrentUserEmail(principal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in"));
        boolean updated = savedJobService.updateJobStateByEmail(email, postingId, request.newState());
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
            @AuthenticationPrincipal Object principal,
            @PathVariable String postingId
    ) {
        String email = getCurrentUserEmail(principal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in"));
        boolean removed = savedJobService.removeSavedJobByEmail(email, postingId);
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
            @AuthenticationPrincipal Object principal
    ) {
        String email = getCurrentUserEmail(principal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in"));
        Map<JobState, Integer> stats = savedJobService.getJobStatsByEmail(email);
        return ResponseEntity.ok(stats);
    }

    // List all saved jobs for the authenticated user
    @GetMapping
    @Operation(summary = "List user's saved jobs")
    @ApiResponse(responseCode = "200", description = "Saved jobs retrieved successfully")
    public ResponseEntity<PageResponse<SavedJobResponse>> getSavedJobs(
            @AuthenticationPrincipal Object principal,
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
        String email = getCurrentUserEmail(principal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in"));
        PageResponse<SavedJobResponse> savedJobs = savedJobService.getSavedJobsByEmail(email, page, cappedSize);
        return ResponseEntity.ok(savedJobs);
    }

    // Extracts the user email from Spring Security to identify the current user and retrieve their database ID.
    private static Optional<String> getCurrentUserEmail(Object principal) {
        if (principal instanceof String principalEmail) {
            if ("anonymousUser".equals(principalEmail) || principalEmail.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(principalEmail);
        }
        if (principal instanceof UserDetails userDetails) {
            return Optional.of(userDetails.getUsername());
        }
        return Optional.empty();
    }
}