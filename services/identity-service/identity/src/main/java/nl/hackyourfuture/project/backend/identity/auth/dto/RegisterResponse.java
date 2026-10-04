package nl.hackyourfuture.project.backend.identity.auth.dto;

import java.util.UUID;

public record RegisterResponse(
        UUID id,
        String email,
        String name,
        String message) {
}