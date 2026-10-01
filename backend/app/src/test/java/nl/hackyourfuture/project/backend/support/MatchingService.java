package nl.hackyourfuture.project.backend.support;

import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.URI;

/**
 * matching-service in a container, one for the whole run (Day 21), started on first use from an image
 * built beforehand; signs with {@link MatchingServiceKey}'s key; reaches identity through job-service's
 * relay, job-service at its container, the model at {@link StubLlm}; reads scores from {@link ScoreTable}
 * (Day 22); Ryuk removes it.
 */
public final class MatchingService {

    /** The image, built from {@code services/matching-service} before the run. */
    public static final String IMAGE = System.getProperty("harness.matching-service.image", "jobmatch-matching-service:harness");

    private static final int PORT = 8080;
    private static final int MANAGEMENT_PORT = 9090;

    private static volatile GenericContainer<?> container;

    private MatchingService() {
    }

    /** The container's mapped application port. */
    public static int port() {
        ensureStarted();
        return container.getMappedPort(PORT);
    }

    /** The container's mapped management port. */
    public static int managementPort() {
        ensureStarted();
        return container.getMappedPort(MANAGEMENT_PORT);
    }

    /** Everything the container has logged this run, for what a request arrived with. */
    public static String logs() {
        ensureStarted();
        return container.getLogs();
    }

    /** Where a client reaches matching-service: its own base URL, container host and mapped port. */
    public static String baseUrl() {
        ensureStarted();
        return "http://" + container.getHost() + ":" + port();
    }

    private static synchronized void ensureStarted() {
        if (container != null) {
            return;
        }
        // Ensure ScoreTable is started so its network is ready
        ScoreTable.client();

        int jobServicePort = JobService.port();
        int jobServiceRelayPort = JobService.relayPort();
        int stubLlmPort = URI.create(StubLlm.instance().baseUrl()).getPort();

        Testcontainers.exposeHostPorts(jobServiceRelayPort, jobServicePort, stubLlmPort);

        GenericContainer<?> matchingService = new GenericContainer<>(DockerImageName.parse(IMAGE))
                // A local build, never a pull: an image by this name on a registry is not ours.
                .withImagePullPolicy(name -> false)
                // Readable by the image's user 1000: a temp file on Linux is 0600, and the copy is root's.
                .withCopyFileToContainer(MountableFile.forHostPath(MatchingServiceKey.pemPath(), 0444),
                        "/run/keys/matching-service.pem")
                .withNetwork(ScoreTable.network())
                .withEnv("SERVICE_JWT_PRIVATE_KEY_FILE", "/run/keys/matching-service.pem")
                .withEnv("INTERNAL_IDENTITY_URL",
                        "http://host.testcontainers.internal:" + jobServiceRelayPort)
                .withEnv("INTERNAL_JOBS_URL",
                        "http://host.testcontainers.internal:" + jobServicePort)
                .withEnv("LLM_BASE_URL",
                        "http://host.testcontainers.internal:" + stubLlmPort)
                .withEnv("LLM_API_KEY", "test-key")
                // DynamoDB scores (Day 22): the table is the harness's, so create is false
                .withEnv("SCORES_DYNAMODB_ENDPOINT", ScoreTable.NETWORK_ENDPOINT)
                .withEnv("AWS_REGION", "eu-west-1")
                .withEnv("AWS_ACCESS_KEY_ID", "dummy")
                .withEnv("AWS_SECRET_ACCESS_KEY", "dummy")
                .withEnv("SCORES_CREATE_TABLE", "false")
                // One line per request, method and path, which matching-service's log pattern prefixes
                // with [traceId,spanId]: what a test reads to see which trace a request arrived under.
                // As JSON, not LOGGING_LEVEL_*: an environment variable's name is lowercased, and
                // logger names are not, so that one matched no logger at all.
                .withEnv("SPRING_APPLICATION_JSON",
                        "{\"logging.level.org.springframework.web.servlet.DispatcherServlet\":\"DEBUG\"}")
                .withExposedPorts(PORT, MANAGEMENT_PORT)
                .waitingFor(Wait.forHttp("/actuator/health/readiness").forPort(MANAGEMENT_PORT));
        try {
            matchingService.start();
        } catch (RuntimeException e) {
            throw new IllegalStateException("matching-service's harness image did not start from " + IMAGE
                    + ". Build it first: docker build -t " + IMAGE + " services/matching-service", e);
        }
        container = matchingService;
    }
}
