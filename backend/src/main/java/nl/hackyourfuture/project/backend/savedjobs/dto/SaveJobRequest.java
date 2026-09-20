package nl.hackyourfuture.project.backend.savedjobs.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "The details required to save a job posting")
public record SaveJobRequest(
        @NotBlank(message = "Posting ID is required")
        @Schema(description = "The unique identifier of the job posting", example = "job-123456")
        String postingId
) {}