package nl.hackyourfuture.project.backend.applications.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import nl.hackyourfuture.project.backend.applications.JobState;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "A saved job item returned with posting details")
public record SavedJobResponse(
        String postingId,
        JobState jobState,
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
        Integer ageDays
) {}