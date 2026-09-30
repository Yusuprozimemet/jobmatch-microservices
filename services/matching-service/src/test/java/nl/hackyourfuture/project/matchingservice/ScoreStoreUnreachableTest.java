package nl.hackyourfuture.project.matchingservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With the score store unreachable (its endpoint a closed port), top-matches still ranks with the
 * model: the failed read is all misses and the failed write is dropped (Day 22, criterion 4).
 * {@link ScoreStoreHungTest} is the same request against a store that never answers.
 */
class ScoreStoreUnreachableTest extends MatchingServiceTest {

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void unreachableStore(DynamicPropertyRegistry registry) throws IOException {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        registry.add("test.scores.endpoint", () -> "http://localhost:" + closed);
        // Nothing to create a table in.
        registry.add("test.scores.create-table", () -> "false");
    }

    @BeforeEach
    void reset() {
        StubUpstream.instance().reset();
        model().reset();
    }

    @Test
    void theModelStillRanks() throws Exception {
        model().willScoreInPromptOrder(77);

        JsonNode matches = TopMatchesCall.forNewUser(port, "unreachable-1").send();

        assertThat(matches.at("/0/aiScored").asBoolean()).isTrue();
        assertThat(model().callCount()).isEqualTo(1);
    }
}
