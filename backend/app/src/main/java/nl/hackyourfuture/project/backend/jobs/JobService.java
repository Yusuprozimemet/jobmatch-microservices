package nl.hackyourfuture.project.backend.jobs;

import nl.hackyourfuture.project.backend.jobs.dto.JobDetailResponse;
import nl.hackyourfuture.project.backend.jobs.dto.JobFiltersResponse;
import nl.hackyourfuture.project.backend.jobs.dto.JobSearchResponse;
import nl.hackyourfuture.project.backend.shared.dto.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class JobService {

    private final JobRepository jobRepository;

    public JobService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    // Search job postings with optional filters, return a paginated list wrapped in PageResponse
    public PageResponse<JobSearchResponse>
    searchJobs(String category, String workMode, String location, String q, int page, int size) {
        return jobRepository.searchJobs(category, workMode, location, q, page, size);
    }

    // Retrieve available filter options for frontend dropdowns
        public JobFiltersResponse getAvailableFilters() {
            return jobRepository.getAvailableFilters();
        }

    // Retrieve a specific job posting or throws a 404 error if not found
    public JobDetailResponse getJobById(String postingId) {
        return jobRepository.getJobById(postingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job posting not found"));
    }
}