package nl.hackyourfuture.project.backend.support;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Which paths belong to which service and where each one is. Services default to the monolith
 * except job-service, matching-service, and application-service, which default to their
 * containers (Days 17, 21 and 25); a test class can point any service at a URL of its own.
 */
public final class Services {

    public static final String JOB_SERVICE = "job-service";
    public static final String MATCHING_SERVICE = "matching-service";
    public static final String APPLICATION_SERVICE = "application-service";

    // JobController's paths and the postings routes: /api/jobs, /api/jobs/filters, /api/jobs/{postingId},
    // /internal/postings/batch and /internal/postings/shortlist, all on job-service (Day 17).
    // top-matches is matching-service's (Day 21).
    // Saved jobs and /internal/saved-counts are application-service's (Day 25).
    private static final List<Service> SERVICES = List.of(
            new Service(JOB_SERVICE, path -> path.equals("/api/jobs")
                    || path.matches("/api/jobs/[^/]+") && !path.equals("/api/jobs/top-matches")
                    || path.startsWith("/internal/postings/")),
            new Service(MATCHING_SERVICE, path -> path.equals("/api/jobs/top-matches")),
            new Service(APPLICATION_SERVICE, path -> path.equals("/api/saved-jobs")
                    || path.startsWith("/api/saved-jobs/")
                    || path.equals("/internal/saved-counts")));

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

    /** The override for this service if present, else its default: job-service, matching-service, and application-service use containers; others the monolith. */
    public static String url(String service, int applicationPort, Map<String, String> overrides) {
        if (overrides.containsKey(service)) {
            return overrides.get(service);
        }
        if (JOB_SERVICE.equals(service)) {
            return "http://localhost:" + JobService.port();
        }
        if (MATCHING_SERVICE.equals(service)) {
            return "http://localhost:" + MatchingService.port();
        }
        if (APPLICATION_SERVICE.equals(service)) {
            return "http://localhost:" + ApplicationService.port();
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
