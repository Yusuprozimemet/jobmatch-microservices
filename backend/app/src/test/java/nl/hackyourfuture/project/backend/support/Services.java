package nl.hackyourfuture.project.backend.support;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Which paths belong to which service and where each one is. A service's URL defaults to the
 * monolith, so the Day 1-4 suite runs unchanged; a test class can point a service at a URL of
 * its own.
 */
public final class Services {

    public static final String JOB_SERVICE = "job-service";

    // JobController's paths: /api/jobs, /api/jobs/filters and /api/jobs/{postingId}. top-matches
    // shares the prefix but is matching's, and stays on the monolith, as in the gateway's routes.
    private static final List<Service> SERVICES = List.of(
            new Service(JOB_SERVICE, path -> path.equals("/api/jobs")
                    || path.matches("/api/jobs/[^/]+") && !path.equals("/api/jobs/top-matches")));

    private record Service(String name, Predicate<String> owns) {
    }

    private Services() {
    }

    /** The service that owns this path, empty for the monolith's own. */
    public static Optional<String> owner(String path) {
        return SERVICES.stream()
                .filter(service -> service.owns.test(path))
                .map(Service::name)
                .findFirst();
    }

    /** The override for this service if present, else the monolith. */
    public static String url(String service, int applicationPort, Map<String, String> overrides) {
        if (overrides.containsKey(service)) {
            return overrides.get(service);
        }
        return "http://localhost:" + applicationPort;
    }

    /** Base URL for this path: the service that owns it, or the monolith. */
    public static String baseUrlFor(String path, int applicationPort, Map<String, String> overrides) {
        return owner(path)
                .map(service -> url(service, applicationPort, overrides))
                .orElse("http://localhost:" + applicationPort);
    }
}
