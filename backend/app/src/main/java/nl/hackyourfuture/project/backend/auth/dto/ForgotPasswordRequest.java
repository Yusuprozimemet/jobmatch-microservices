package nl.hackyourfuture.project.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "The details required to request a password reset email")
public record ForgotPasswordRequest(
        @NotBlank @Email
        @Schema(description = "Email address associated with the account", example = "user@example.com")
        String email
) {}