package nl.hackyourfuture.project.backend.auth.dto;

import java.util.UUID;

public record RegisterResponse(
        UUID id,
        String email,
        String name,
        String message) {
}