package nl.hackyourfuture.project.backend.identity.auth;

import jakarta.servlet.http.HttpSession;

import java.util.Optional;

// A Google identity waiting in the session until a password login proves the account.
final class PendingGoogleLink {

    private static final String EMAIL = PendingGoogleLink.class.getName() + ".email";
    private static final String PROVIDER_ID = PendingGoogleLink.class.getName() + ".providerId";

    private PendingGoogleLink() {
    }

    static void save(HttpSession session, String email, String providerId) {
        session.setAttribute(EMAIL, email);
        session.setAttribute(PROVIDER_ID, providerId);
    }

    // Returns the parked id once, only to a login for the same email.
    static Optional<String> claim(HttpSession session, String email) {
        if (session == null || !email.equalsIgnoreCase((String) session.getAttribute(EMAIL))) {
            return Optional.empty();
        }
        String providerId = (String) session.getAttribute(PROVIDER_ID);
        session.removeAttribute(EMAIL);
        session.removeAttribute(PROVIDER_ID);
        return Optional.ofNullable(providerId);
    }
}
