package nl.hackyourfuture.project.matchingservice;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model call's bulkhead is wired: without {@code spring-boot-starter-aspectj},
 * {@code @Bulkhead} does nothing and no error says so. A bulkhead the yaml does not name gets
 * resilience4j's defaults (25 concurrent calls), so the instance's config is read, not only its
 * existence.
 */
class BulkheadConfigTest extends MatchingServiceTest {

    @Autowired
    private BulkheadRegistry bulkheadRegistry;

    @Test
    void bulkheadIsConfiguredAsTheSpecSays() {
        Bulkhead bulkhead = bulkheadRegistry.bulkhead("modelCall");
        var config = bulkhead.getBulkheadConfig();

        assertThat(config.getMaxConcurrentCalls()).isEqualTo(10);
        assertThat(config.getMaxWaitDuration()).isEqualTo(Duration.ZERO);
    }
}
