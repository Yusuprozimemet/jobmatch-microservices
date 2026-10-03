package nl.hackyourfuture.project.applicationservice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the internal clients send their calls: identity for the existence check and its key set,
 * and job-service for the posting lookup. Both are required. In the monolith an empty URL meant
 * "this process"; this process serves none of those routes, so without both it does not start.
 */
@ConfigurationProperties("app.internal")
public record InternalUrls(String identityUrl, String jobsUrl) {

    public InternalUrls {
        require(identityUrl, "app.internal.identity-url (INTERNAL_IDENTITY_URL)");
        require(jobsUrl, "app.internal.jobs-url (INTERNAL_JOBS_URL)");
    }

    private static void require(String url, String name) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
    }
}
