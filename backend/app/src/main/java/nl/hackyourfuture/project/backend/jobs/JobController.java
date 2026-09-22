package nl.hackyourfuture.project.backend.jobs;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import nl.hackyourfuture.project.backend.jobs.dto.JobDetailResponse;
import nl.hackyourfuture.project.backend.jobs.dto.JobFiltersResponse;
import nl.hackyourfuture.project.backend.jobs.dto.JobSearchResponse;
import nl.hackyourfuture.project.backend.jobs.dto.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Jobs", description = "Endpoints for searching and viewing job postings")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    // search and filter job postings
    @GetMapping
    @Operation(summary = "Search job postings with optional filters")
    @ApiResponse(responseCode = "200", description = "Jobs retrieved successfully")
    public PageResponse<JobSearchResponse> searchJobs(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String workMode,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, // Default to first page (index 0)
            @RequestParam(defaultValue = "20") int size // Default to 20 items per page
    ) {
        // Validate page index (must be >= 0)
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page index must be greater than or equal to 0");
        }

        // Validate page size (must be >= 1)
        if (size < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size must be greater than 0");
        }

        // Cap maximum page size to prevent loading the whole database (e.g. max 100 items per page)
        int cappedSize = Math.min(size, 100);

        // Forward sanitized page and cappedSize to JobService
        return jobService.searchJobs(category, workMode, location, q, page, cappedSize);

    }

    // retrieve available filter options for search dropdowns
    @GetMapping("/filters")
        @Operation(summary = "Get available filter options for search dropdowns")
        @ApiResponse(responseCode = "200", description = "Filters retrieved successfully")
        public ResponseEntity<JobFiltersResponse> getJobFilters() {
            JobFiltersResponse filters = jobService.getAvailableFilters();
            return ResponseEntity.ok(filters);
        }

    // fetch complete details of a single job posting by ID
    @GetMapping("/{postingId}")
    @Operation(summary = "Get full details of a single job posting by ID")
    @ApiResponse(responseCode = "200", description = "Job details retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Job posting not found")
    public ResponseEntity<JobDetailResponse> getJobById(
            @PathVariable String postingId
    ) {
        JobDetailResponse job = jobService.getJobById(postingId);
        return ResponseEntity.ok(job);
    }
}