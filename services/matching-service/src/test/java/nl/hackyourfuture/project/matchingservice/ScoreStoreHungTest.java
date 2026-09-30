package nl.hackyourfuture.project.matchingservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With the score store hung (its container, this class's own, paused), top-matches still ranks with
 * the model within 3 s (Day 22, criterion 4): the client's 500 ms call timeout bounds the read and
 * each write. Under 3 s in all is stricter than the criterion's 3 s more than the unreachable case.
 */
class ScoreStoreHungTest extends MatchingServiceTest {

    private static final GenericContainer<?> STORE = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:3.3.1"))
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
            .withExposedPorts(8000)
            .waitingFor(Wait.forListeningPort());

    static {
        STORE.start();
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void ownStore(DynamicPropertyRegistry registry) {
        registry.add("test.scores.endpoint", () -> "http://" + STORE.getHost() + ":" + STORE.getMappedPort(8000));
    }

    @BeforeEach
    void reset() {
        StubUpstream.instance().reset();
        model().reset();
    }

    @Test
    void theModelStillRanksWithinThreeSeconds() throws Exception {
        TopMatchesCall call = TopMatchesCall.forNewUser(port, "hung-1");
        model().willScoreInPromptOrder(88);

        STORE.getDockerClient().pauseContainerCmd(STORE.getContainerId()).exec();
        long start = System.nanoTime();
        JsonNode matches;
        try {
            matches = call.send();
        } finally {
            STORE.getDockerClient().unpauseContainerCmd(STORE.getContainerId()).exec();
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(matches.at("/0/aiScored").asBoolean()).isTrue();
        assertThat(model().callCount()).isEqualTo(1);
        assertThat(elapsedMillis).isLessThan(3_000);
    }
}
