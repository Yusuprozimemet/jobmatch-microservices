package nl.hackyourfuture.project.applicationservice;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application's own registry refuses the scheduled task and security filter chain observations,
 * so they never start a trace (Day 43, C43.17). The request observation still starts: that is what
 * shows the registry is live, so the refusals above prove something.
 */
class ObservationFilterTest extends ApplicationServiceTest {

    @Autowired
    private ObservationRegistry registry;

    @ParameterizedTest
    @ValueSource(strings = {
            "tasks.scheduled.execution",
            "spring.security.filterchains",
            "spring.security.http.secured.requests"})
    void schedulerAndSecurityObservationsAreNotStarted(String name) {
        assertThat(Observation.createNotStarted(name, registry).isNoop()).isTrue();
    }

    @Test
    void requestObservationsAreStarted() {
        assertThat(Observation.createNotStarted("http.server.requests", registry).isNoop()).isFalse();
    }
}
