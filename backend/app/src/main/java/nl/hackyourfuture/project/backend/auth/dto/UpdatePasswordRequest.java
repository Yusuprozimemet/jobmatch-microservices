package nl.hackyourfuture.project.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "The details required to update an active user's password")
public record UpdatePasswordRequest(
        @NotBlank(message = "Current password is required")
        @Schema(description = "The user's current password", example = "OldPassword123")
        String currentPassword,

        @NotBlank(message = "New password is required")
        @Size(min = 6, message = "Password must be at least 6 characters")
        @Schema(description = "The new password to set", example = "NewSecurePassword456")
        String newPassword
) {}