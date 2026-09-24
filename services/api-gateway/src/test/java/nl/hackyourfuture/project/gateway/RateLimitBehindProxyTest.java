package nl.hackyourfuture.project.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static nl.hackyourfuture.project.gateway.RateLimitTest.post;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behind a trusted proxy (the frontend, from Day 16) the client is the address the proxy added to
 * {@code X-Forwarded-For}, so each browser has its own bucket rather than all sharing the proxy's.
 * Here the tests connect from localhost, which this context trusts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.trusted-proxies=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1")
class RateLimitBehindProxyTest {

    private static final RecordingUpstream BACKEND = new RecordingUpstream();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("gateway.backend-url", BACKEND::url);
    }

    @AfterAll
    static void stop() {
        BACKEND.close();
    }

    @BeforeEach
    void clear() {
        BACKEND.clear();
    }

    @Test
    void oneBrowsersEleventhLoginIsRefusedAndAnotherBrowserStillGetsThrough() throws Exception {
        for (int i = 1; i <= 10; i++) {
            assertThat(post(port, "/api/auth/login", "203.0.113.7").statusCode()).as("login %d", i).isEqualTo(200);
        }

        assertThat(post(port, "/api/auth/login", "203.0.113.7").statusCode()).isEqualTo(429);
        assertThat(post(port, "/api/auth/login", "203.0.113.8").statusCode()).as("another browser").isEqualTo(200);
        assertThat(BACKEND.received()).hasSize(11);
    }

    @Test
    void theAddressTheProxyAddedCountsNotOneTheClientWroteBeforeIt() throws Exception {
        for (int i = 1; i <= 10; i++) {
            assertThat(post(port, "/api/auth/register", "10.9.9." + i + ", 198.51.100.4").statusCode())
                    .as("register %d", i).isEqualTo(200);
        }

        assertThat(post(port, "/api/auth/register", "10.9.9.11, 198.51.100.4").statusCode()).isEqualTo(429);
    }
}
