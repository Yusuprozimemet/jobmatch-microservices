package nl.hackyourfuture.project.backend.identity;

import java.util.Optional;

/**
 * The email a logged-in principal carries. Empty for nobody logged in, or for Spring's
 * anonymous placeholder.
 *
 * <p>The principal is always a plain email: password login and Google sign-in both end in
 * {@code AuthenticationService.establishSession}, which puts the account's email there. The
 * helper this replaces, copied into four controllers, also accepted a {@code UserDetails}; nothing
 * creates one, and removing that branch left every test green.
 *
 * <p>Used by {@link CurrentUserIdResolver} for the other modules, and by identity's own
 * controllers, which still work by email. Day 13 puts the id in a token and changes this.
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
