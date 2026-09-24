package nl.hackyourfuture.project.backend.shared.internal;

/**
 * The bearer token a module attaches when it calls another service's {@code /internal/**} route
 * (Day 39): signed with this service's own key, and verified by the service it calls. Modules name
 * this interface, never its implementation in {@code app}. Day 19's clients are the first to use it.
 */
public interface ServiceToken {

    /** A new, short-lived token proving this service to another. */
    String mint();
}
