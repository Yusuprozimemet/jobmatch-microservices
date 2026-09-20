package nl.hackyourfuture.project.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Name is required") String name,
        @NotBlank(message = "Email is required") @Email String email,
        @NotBlank(message = "Password is required") @Size(min = 6, message = "Password must be at least 6 characters") String password,
        // Enforced here, not by the frontend checkbox.
        // Boxed, so a missing field fails validation instead of the JSON parse.
        // @AssertTrue ignores null, so @NotNull covers the missing case.
        @NotNull(message = "You must accept the terms and privacy policy")
        @AssertTrue(message = "You must accept the terms and privacy policy")
        @Schema(
                description = "Whether the user ticked the terms and privacy box. Must be true.",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Boolean acceptedTerms) {
}
