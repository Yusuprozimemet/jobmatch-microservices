package nl.hackyourfuture.project.backend.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import nl.hackyourfuture.project.backend.profile.Profile;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Schema(description = "The job preferences saved by a user")
public record ProfileResponse(
        @Schema(
                description = "The account these preferences belong to, always the caller's own",
                example = "effe1126-329f-4f31-942c-31bc0be4d672",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        UUID userId,
        @Schema(
                description = "Skills the user has, as they were spelled when saved. Never null: "
                        + "a user who has picked none gets an empty list, so the caller can render "
                        + "it without a check.",
                example = "[\"React\", \"TypeScript\"]",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        List<String> skills,
        @Schema(description = "The field of work aimed for", example = "frontend")
        String category,
        @Schema(description = "Where the user wants to work", example = "Utrecht")
        String preferredCity,
        @Schema(description = "Remote, hybrid or on-site", example = "remote")
        String workMode,
        @Schema(description = "How far into their career the user is", example = "entry")
        String experienceLevel,
        @Schema(description = "Full-time, part-time, contract or internship", example = "full-time")
        String employmentType,
        @Schema(
                description = "Gross yearly salary the user is aiming for, in euros",
                example = "45000.00"
        )
        BigDecimal salaryPreference
) {
    public static ProfileResponse from(Profile profile) {
        return new ProfileResponse(
                profile.getUserId(),
                profile.getSkills(),
                profile.getCategory(),
                profile.getPreferredCity(),
                profile.getWorkMode(),
                profile.getExperienceLevel(),
                profile.getEmploymentType(),
                profile.getSalaryPreference()
        );
    }
}
