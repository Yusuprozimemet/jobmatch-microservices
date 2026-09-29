package nl.hackyourfuture.project.backend.shared.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * The verified token's {@code sub} (the user id, Day 41). Carried as the authentication's
 * details, so the principal stays the email, unchanged.
 *
 * <p>A verified {@code sub} needs no assumption about who can reach the service, unlike the
 * gateway's {@code X-User-Id}. {@code X-User-Id} is only as good as the rule that nothing but
 * the gateway reaches matching-service; the harness and compose both call services directly.
 *
 * @param userId the UUID from the token's {@code sub} claim
 */
public record TokenSubject(UUID userId) {

    /**
     * The current thread's verified user id, read from {@code SecurityContextHolder}, or empty
     * if no authentication is present or its details are not a {@code TokenSubject}.
     */
    public static Optional<UUID> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getDetails() instanceof TokenSubject subject
                ? Optional.of(subject.userId())
                : Optional.empty();
    }
}
