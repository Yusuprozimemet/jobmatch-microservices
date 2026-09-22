package nl.hackyourfuture.project.backend.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

// PUT replaces the whole profile - leaving a field out clears it. Skills are required,
// so missing skills is a 400 instead of clearing them.
@Schema(description = "The job preferences to save. Replaces the profile: an optional field "
        + "left out is cleared. Skills are required and cannot be cleared this way.")
public record UpdateProfileRequest(
        // Must be 5-20 skills. Only checks what was sent - ProfileService re-checks after
        // cleanup, since duplicates can drop the count.
        @NotNull(message = "Skills are required")
        @Size(min = MIN_SKILLS, max = MAX_SKILLS,
                message = "Select between " + MIN_SKILLS + " and " + MAX_SKILLS + " skills")
        @Schema(description = "Skills the user has, between 5 and 20 of them once blanks and "
                + "duplicates are removed. Spelling is kept as sent; skills that differ only "
                + "in case or spacing are collapsed to one and then counted again.",
                example = "[\"React\", \"TypeScript\", \"Node.js\", \"PostgreSQL\", \"Docker\"]")
        List<@Size(max = 100, message = "A skill may be at most 100 characters") String> skills,

        // Matches the DB column limit, so an over-long value is a 400, not a DB error.
        @Size(max = 255, message = "Category may be at most 255 characters")
        @Schema(description = "The field of work aimed for", example = "frontend")
        String category,

        @Size(max = 255, message = "Preferred city may be at most 255 characters")
        @Schema(description = "Where the user wants to work", example = "Utrecht")
        String preferredCity,

        @Size(max = 255, message = "Work mode may be at most 255 characters")
        @Schema(description = "Remote, hybrid or on-site", example = "remote")
        String workMode,

        @Size(max = 255, message = "Experience level may be at most 255 characters")
        @Schema(description = "How far into their career the user is", example = "entry")
        String experienceLevel,

        @Size(max = 255, message = "Employment type may be at most 255 characters")
        @Schema(description = "Full-time, part-time, contract or internship", example = "full-time")
        String employmentType,

        // Matches the DB precision: 8 digits before the point, 2 after.
        @DecimalMin(value = "0", message = "Salary preference cannot be negative")
        @Digits(integer = 8, fraction = 2,
                message = "Salary preference may have at most 8 digits and 2 decimals")
        @Schema(description = "Gross yearly salary the user is aiming for, in euros", example = "45000")
        BigDecimal salaryPreference
) {

    // Matching needs at least this many skills to rank on.
    public static final int MIN_SKILLS = 5;
    public static final int MAX_SKILLS = 20;
}
