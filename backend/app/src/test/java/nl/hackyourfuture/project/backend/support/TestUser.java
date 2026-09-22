package nl.hackyourfuture.project.backend.support;

import java.util.UUID;

/**
 * A user that exists in the database.
 *
 * <p>Carries the plaintext password as well as the id, because {@code authenticatedAs} logs in
 * over the real endpoint rather than forging a security context.
 * {@code password} is null for a Google-only account.
 */
public record TestUser(UUID id, String email, String name, String password) {
}
