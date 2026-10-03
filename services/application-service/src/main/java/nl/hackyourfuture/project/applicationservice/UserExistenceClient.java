package nl.hackyourfuture.project.applicationservice;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import nl.hackyourfuture.project.backend.shared.internal.InternalClients;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Whether a user still exists (Day 25): identity's {@code /internal/users/{id}} over HTTP
 * ({@code app.internal.identity-url}). A copy of matching-service's (Day 41), with its own message.
 *
 * <p>A 404 is a user that was deleted: false, the same answer as a missing account would be. An
 * outage (a connection or timeout error, a 5xx, the breaker open) throws a
 * {@code ResponseStatusException} with 503: the identity service is unreachable. A 4xx other
 * than 404 is a bug on this side, not an outage, so it is not caught and the caller answers 500.
 *
 * <p>Not cached and no retry, for matching-service's reasons: an answer cached for N seconds lets a
 * deleted user in for N seconds, and a retry on the 2 s read timeout doubles the wait when the
 * breaker already covers an outage.
 */
@Component
class UserExistenceClient {

    private final Supplier<RestClient> identity;

    UserExistenceClient(InternalClients internalClients) {
        this.identity = internalClients.forUrlProperty("app.internal.identity-url");
    }

    @CircuitBreaker(name = "userExistence", fallbackMethod = "unavailable")
    boolean exists(UUID userId) {
        try {
            identity.get().get()
                    .uri("/internal/users/{id}", userId)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            // Caught here, so the breaker counts it as the answer it is, not a failure.
            return false;
        }
    }

    // Chosen by the exception's type; anything else, a 4xx among it, is rethrown.
    boolean unavailable(UUID userId, ResourceAccessException e) {
        throw userUnreachable();
    }

    boolean unavailable(UUID userId, HttpServerErrorException e) {
        throw userUnreachable();
    }

    boolean unavailable(UUID userId, CallNotPermittedException e) {
        throw userUnreachable();
    }

    private static ResponseStatusException userUnreachable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Your account could not be checked; saved jobs are temporarily unavailable. Try again shortly.");
    }
}
