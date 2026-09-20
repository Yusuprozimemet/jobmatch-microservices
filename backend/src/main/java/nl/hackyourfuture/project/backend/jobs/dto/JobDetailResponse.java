package nl.hackyourfuture.project.backend.jobs.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "Full details of a single job posting")
public record JobDetailResponse(
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
        String description,
        String experienceLevel,
        String educationLevel,
        Double salaryMin,
        Double salaryMax,
        String salaryCurrency,
        String salaryPeriod,
        String sourceUrl,
        String status,
        int savedCount
) {}
