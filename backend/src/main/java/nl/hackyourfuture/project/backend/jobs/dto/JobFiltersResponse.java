package nl.hackyourfuture.project.backend.jobs.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Available filter options for job search dropdowns")
public record JobFiltersResponse(
        List<String> locations,
        List<String> categories,
        List<String> workModes,
        List<String> experienceLevels,
        List<String> employmentTypes
) {}