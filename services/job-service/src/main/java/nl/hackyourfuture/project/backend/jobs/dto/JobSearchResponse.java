package nl.hackyourfuture.project.backend.jobs.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "Summary details of a job posting for search results")
public record JobSearchResponse(
        String postingId,
        String title,
        String companyName,
        String location,
        String workMode,
        Boolean isRemote,
        List<String> skills,
        String employmentType,
        LocalDate postedDate,
        String source,
        String category,
        String freshnessClass,
        Integer ageDays,
        int savedCount
) {

    public JobSearchResponse withSavedCount(int count) {
        return new JobSearchResponse(postingId, title, companyName, location, workMode, isRemote, skills,
                employmentType, postedDate, source, category, freshnessClass, ageDays, count);
    }
}
