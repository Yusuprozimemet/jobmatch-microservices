package nl.hackyourfuture.project.applicationservice;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/** Fails every call behind the postingLookup breaker, so {@code BreakerConfigTest} can open it. */
@Component
class BreakerTestComponent {

    @CircuitBreaker(name = "postingLookup")
    void callThatThrows() {
        throw new ResourceAccessException("down");
    }
}
