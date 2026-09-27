package nl.hackyourfuture.project.backend.jobs;

import nl.hackyourfuture.project.backend.jobs.dto.JobDetailResponse;
import nl.hackyourfuture.project.backend.jobs.dto.JobFiltersResponse;
import nl.hackyourfuture.project.backend.jobs.dto.JobSearchResponse;
import nl.hackyourfuture.project.backend.shared.applications.SavedJobCounts;
import nl.hackyourfuture.project.backend.shared.dto.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final SavedJobCounts savedJobCounts;

    public JobService(JobRepository jobRepository, SavedJobCounts savedJobCounts) {
        this.jobRepository = jobRepository;
        this.savedJobCounts = savedJobCounts;
    }

    // Search job postings with optional filters, return a paginated list wrapped in PageResponse.
    // savedCount comes from applications, which owns the saves: one call for the whole page,
    // made here rather than in JobRepository so the repository only reads what jobs owns.
    public PageResponse<JobSearchResponse>
    searchJobs(String category, String workMode, String location, String q, int page, int size) {
        PageResponse<JobSearchResponse> postings =
                jobRepository.searchJobs(category, workMode, location, q, page, size);
        Map<String, Integer> counts = savedJobCounts.countsFor(
                postings.content().stream().map(JobSearchResponse::postingId).toList());
        List<JobSearchResponse> content = postings.content().stream()
                .map(posting -> posting.withSavedCount(counts.get(posting.postingId())))
                .toList();
        return PageResponse.of(content, postings.page(), postings.size(), postings.totalElements());
    }

    // Retrieve available filter options for frontend dropdowns
        public JobFiltersResponse getAvailableFilters() {
            return jobRepository.getAvailableFilters();
        }

    // Retrieve a specific job posting or throws a 404 error if not found
    public JobDetailResponse getJobById(String postingId) {
        return jobRepository.getJobById(postingId)
                .map(posting -> posting.withSavedCount(
                        savedJobCounts.countsFor(List.of(postingId)).get(postingId)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job posting not found"));
    }
}