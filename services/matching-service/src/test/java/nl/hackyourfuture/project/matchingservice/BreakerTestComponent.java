package nl.hackyourfuture.project.matchingservice;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/** Fails every call behind the postingShortlist breaker, so {@code BreakerConfigTest} can open it. */
@Component
class BreakerTestComponent {

    @CircuitBreaker(name = "postingShortlist")
    void callThatThrows() {
        throw new ResourceAccessException("down");
    }
}
