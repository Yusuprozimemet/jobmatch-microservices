package nl.hackyourfuture.project.backend.shared.internal;

/**
 * The bearer token a module attaches when it calls another service's {@code /internal/**} route
 * (Day 39): signed with this service's own key, and verified by the service it calls. Callers name
 * this interface; matching-service's implementation is {@code ServiceTokens}.
 *
 * <p>This is matching-service's copy of {@code backend.shared.internal.ServiceToken}. matching-service
 * copies what {@code matching} uses: its image is built from its own folder and sees nothing of
 * {@code backend/} (Day 21).
 */
public interface ServiceToken {

    /** A new, short-lived token proving this service to another. */
    String mint();
}
