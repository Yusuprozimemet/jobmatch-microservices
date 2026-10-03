package nl.hackyourfuture.project.backend.support;

import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.URI;

/**
 * application-service in a container, one for the whole run (Day 25), started on first use from an
 * image built beforehand; signs with {@link ServiceKey#APPLICATION}'s key; reads and writes apps_db
 * as applications_user; reaches identity through job-service's relay, job-service at its container;
 * trusts job-service and TestServiceCaller on /internal/**; its consumer reads a queue of its own
 * ({@link EventBus#APPLICATION_SERVICE_QUEUE}); no path routes to it until Track E1; Ryuk removes it.
 */
public final class ApplicationService {

    /** The image, built from {@code services/application-service} before the run. */
    public static final String IMAGE = System.getProperty("harness.application-service.image",
            "jobmatch-application-service:harness");

    private static final int PORT = 8080;
    private static final int MANAGEMENT_PORT = 9090;

    private static volatile GenericContainer<?> container;
    private static volatile String consumerQueueUrlValue;

    private ApplicationService() {
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

    /** Where a client reaches application-service: its own base URL, container host and mapped port. */
    public static String baseUrl() {
        ensureStarted();
        return "http://" + container.getHost() + ":" + port();
    }

    /** The EVENTS_USER_DELETED_QUEUE_URL the container was given. */
    public static String consumerQueueUrl() {
        ensureStarted();
        return consumerQueueUrlValue;
    }

    private static synchronized void ensureStarted() {
        if (container != null) {
            return;
        }

        int postgresPort = PostgresContainer.instance().getMappedPort(5432);
        int jobServicePort = JobService.port();
        int jobServiceRelayPort = JobService.relayPort();
        int testCallerPort = URI.create(TestServiceCaller.instance().jwksUrl()).getPort();
        int eventBusPort = EventBus.endpoint().getPort();

        Testcontainers.exposeHostPorts(postgresPort, jobServiceRelayPort, jobServicePort,
                testCallerPort, eventBusPort);

        String eventBusEndpoint = "http://host.testcontainers.internal:" + eventBusPort;
        String consumerQueueUrl = eventBusEndpoint + URI.create(EventBus.queueUrl(
                EventBus.APPLICATION_SERVICE_QUEUE)).getPath();
        consumerQueueUrlValue = consumerQueueUrl;

        GenericContainer<?> applicationService = new GenericContainer<>(DockerImageName.parse(IMAGE))
                // A local build, never a pull: an image by this name on a registry is not ours.
                .withImagePullPolicy(name -> false)
                // Readable by the image's user 1000: a temp file on Linux is 0600, and the copy is root's.
                .withCopyFileToContainer(MountableFile.forHostPath(ServiceKey.APPLICATION.pemPath(), 0444),
                        "/run/keys/application-service.pem")
                .withEnv("SERVICE_JWT_PRIVATE_KEY_FILE", "/run/keys/application-service.pem")
                .withEnv("DB_HOST", "host.testcontainers.internal")
                .withEnv("DB_PORT", String.valueOf(postgresPort))
                .withEnv("DB_NAME", PostgresContainer.APPS_DATABASE)
                .withEnv("DB_APPLICATIONS_USER", "applications_user")
                .withEnv("DB_APPLICATIONS_PASSWORD", PostgresContainer.rolePassword())
                .withEnv("INTERNAL_IDENTITY_URL",
                        "http://host.testcontainers.internal:" + jobServiceRelayPort)
                .withEnv("INTERNAL_JOBS_URL",
                        "http://host.testcontainers.internal:" + jobServicePort)
                .withEnv("JOB_SERVICE_KEY_SET_URL",
                        "http://host.testcontainers.internal:" + jobServicePort + "/.well-known/service-jwks.json")
                .withEnv("APP_INTERNAL_TRUSTEDISSUERS_0_NAME", TestServiceCaller.ISSUER)
                .withEnv("APP_INTERNAL_TRUSTEDISSUERS_0_KEYSETURL",
                        "http://host.testcontainers.internal:" + testCallerPort + "/.well-known/service-jwks.json")
                // The consumer's settings, read once Track E1 moves the consumer in; the queue is its
                // own so it never takes the shared queue's messages.
                .withEnv("EVENTS_CONSUMER_ENABLED", "true")
                .withEnv("EVENTS_SQS_ENDPOINT", eventBusEndpoint)
                .withEnv("EVENTS_SQS_REGION", "eu-west-1")
                .withEnv("EVENTS_SQS_ACCESS_KEY", "dummy")
                .withEnv("EVENTS_SQS_SECRET_KEY", "dummy")
                .withEnv("EVENTS_USER_DELETED_QUEUE_URL", consumerQueueUrl)
                // One line per request, method and path, which application-service's log pattern prefixes
                // with [traceId,spanId]: what a test reads to see which trace a request arrived under.
                // As JSON, not LOGGING_LEVEL_*: an environment variable's name is lowercased, and
                // logger names are not, so that one matched no logger at all.
                .withEnv("SPRING_APPLICATION_JSON",
                        "{\"logging.level.org.springframework.web.servlet.DispatcherServlet\":\"DEBUG\"}")
                .withExposedPorts(PORT, MANAGEMENT_PORT)
                .waitingFor(Wait.forHttp("/actuator/health/readiness").forPort(MANAGEMENT_PORT));
        try {
            applicationService.start();
        } catch (RuntimeException e) {
            throw new IllegalStateException("application-service's harness image did not start from "
                    + IMAGE + ". Build it first: docker build -t " + IMAGE + " services/application-service", e);
        }
        container = applicationService;
    }
}
