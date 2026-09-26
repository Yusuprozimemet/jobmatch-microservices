package nl.hackyourfuture.project.backend.shared.internal;

/**
 * The bearer token a module attaches when it calls another service's {@code /internal/**} route
 * (Day 39): signed with this service's own key, and verified by the service it calls. Callers name
 * this interface; job-service's implementation is {@code ServiceTokens}.
 *
 * <p>This is job-service's copy of {@code backend.shared.internal.ServiceToken}. job-service
 * copies what {@code jobs} uses: its image is built from its own folder and sees nothing of
 * {@code backend/} (Day 17).
 */
public interface ServiceToken {

    /** A new, short-lived token proving this service to another. */
    String mint();
}
