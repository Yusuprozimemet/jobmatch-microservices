package nl.hackyourfuture.project.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "The details required to reset a user password using a token")
public record ResetPasswordRequest(
        @NotBlank
        @Schema(description = "The secure reset token received via email", example = "123e4567-e89b-12d3-a456-426614174000")
        String token,

        @NotBlank
        @Size(min = 6, message = "Password must be at least 6 characters")
        @Schema(description = "The new password to set", example = "NewSecurePassword123")
        String newPassword
) {}