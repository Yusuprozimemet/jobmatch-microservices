package nl.hackyourfuture.project.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

public record LoginResponse(
        String email,
        String name,
        @Schema(
                description = "When the user agreed to the terms and privacy policy. "
                        + "Null means they never did - show the agreement before continuing.",
                example = "2026-08-25T14:30:00Z"
        )
        OffsetDateTime termsAcceptedAt) {
}
