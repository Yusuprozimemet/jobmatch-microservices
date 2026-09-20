package nl.hackyourfuture.project.backend.savedjobs.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import nl.hackyourfuture.project.backend.savedjobs.JobState;

@Schema(description = "The details required to update the state of a saved job")
public record UpdateJobStateRequest(
        @NotNull(message = "New job state is required")
        @Schema(description = "The new state to move the saved job into", example = "APPLIED")
        JobState newState
) {}