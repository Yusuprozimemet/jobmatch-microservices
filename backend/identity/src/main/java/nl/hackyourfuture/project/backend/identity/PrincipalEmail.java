package nl.hackyourfuture.project.backend.identity;

import java.util.Optional;

/**
 * The email a logged-in principal carries. Empty for nobody logged in, or for Spring's
 * anonymous placeholder.
 *
 * <p>The principal is always a plain email: since Day 13 it is the access token's {@code email}
 * claim, which {@code AccessTokenAuthentication} puts there as the session used to. The
 * helper this replaces, copied into four controllers, also accepted a {@code UserDetails}; nothing
 * creates one, and removing that branch left every test green.
 *
 * <p>Used by {@link CurrentUserIdResolver} for the other modules, and by identity's own
 * controllers, which still work by email.
 */
public final class PrincipalEmail {

    private PrincipalEmail() {
    }

    public static Optional<String> of(Object principal) {
        if (principal instanceof String email && !"anonymousUser".equals(email) && !email.isBlank()) {
            return Optional.of(email);
        }
        return Optional.empty();
    }
}
