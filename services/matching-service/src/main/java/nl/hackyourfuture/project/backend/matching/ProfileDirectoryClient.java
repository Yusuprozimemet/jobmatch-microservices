package nl.hackyourfuture.project.backend.matching;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import nl.hackyourfuture.project.backend.shared.identity.ProfileDirectory;
import nl.hackyourfuture.project.backend.shared.identity.ProfileSnapshot;
import nl.hackyourfuture.project.backend.shared.internal.InternalClients;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The {@code ProfileDirectory} top matches uses (Day 41): {@code identity}'s
 * {@code /internal/profiles/{userId}} over HTTP ({@code app.internal.identity-url}), empty for this
 * process until Day 21 points it at identity's service. {@code @Primary}, so it serves; the route
 * answers from identity's {@code IdentityDirectory}.
 *
 * <p>A 404 is a user with no profile: empty, the caller's 422. An outage (a connection or timeout
 * error, a 5xx, the breaker open) throws a {@code ResponseStatusException} with 503: there is
 * nothing to rank on. A 4xx other than 404 is a bug on this side, not an outage, so it is not caught
 * and the caller answers 500.
 *
 * <p>No retry: the read is idempotent, but a retry on the 2 s read timeout doubles the wait on the
 * path the phase exists to protect, and the breaker already covers an outage. Day 24 measured
 * it across the network: 160 calls in compose, mean 5.7-7.2 ms, no error of any kind, so no
 * transient error a retry would have saved; no retry. Whether to cache the profile is Track B's
 * question on Day 24 (the call was 16% of top-matches' server time without the model, above the
 * spec's 10% threshold), not this client's.
 */
@Component
@Primary
class ProfileDirectoryClient implements ProfileDirectory {

    private final Supplier<RestClient> identity;

    ProfileDirectoryClient(InternalClients internalClients) {
        this.identity = internalClients.forUrlProperty("app.internal.identity-url");
    }

    @Override
    @CircuitBreaker(name = "profileDirectory", fallbackMethod = "unavailable")
    public Optional<ProfileSnapshot> forUser(UUID userId) {
        try {
            ProfileSnapshot answer = identity.get().get()
                    .uri("/internal/profiles/{userId}", userId)
                    .retrieve()
                    .body(ProfileSnapshot.class);
            return Optional.ofNullable(answer);
        } catch (HttpClientErrorException.NotFound e) {
            // Caught here, so the breaker counts it as the answer it is, not a failure.
            return Optional.empty();
        }
    }

    // Chosen by the exception's type; anything else, a 4xx among it, is rethrown.
    Optional<ProfileSnapshot> unavailable(UUID userId, ResourceAccessException e) {
        throw profileUnreachable();
    }

    Optional<ProfileSnapshot> unavailable(UUID userId, HttpServerErrorException e) {
        throw profileUnreachable();
    }

    Optional<ProfileSnapshot> unavailable(UUID userId, CallNotPermittedException e) {
        throw profileUnreachable();
    }

    private static ResponseStatusException profileUnreachable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "The profile could not be reached; matching is temporarily unavailable. Try again shortly.");
    }
}
