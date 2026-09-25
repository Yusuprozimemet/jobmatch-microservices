package nl.hackyourfuture.project.backend.support;

import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a test's requests go: straight to the application, or, with {@code -Dharness.gateway=true},
 * through the API gateway in front of it (Day 15).
 *
 * <p>The gateway runs in a container from an image built beforehand, as CI does:
 * {@code docker build -t jobmatch-api-gateway:harness services/api-gateway}. Testcontainers cannot
 * build it itself: the Dockerfile's {@code --platform=$BUILDPLATFORM} needs BuildKit. It is routed at the test's
 * own application through {@code host.testcontainers.internal}. The application stays in this JVM,
 * so the in-JVM stubs and the JDBC fixtures work unchanged. The gateway, not the harness, routes by
 * path in this run; it is started with {@code JOB_SERVICE_URL} from the route table. One gateway per
 * application port and route table: each Spring test context is its own application, and a test class
 * that points job paths at a stub must not share a gateway with the rest of the suite. Started on first
 * use and never stopped; Ryuk removes them when the JVM exits, as it does the Postgres container.
 *
 * <p>An addition to the direct run, never a replacement: only the direct run can show the backend
 * still checks tokens itself.
 */
public final class Gateway {

    /** The switch. Off unless set, so a plain {@code verify} is the direct run. */
    public static final boolean ON = Boolean.getBoolean("harness.gateway");

    /** The image, built from {@code services/api-gateway} before the run. */
    public static final String IMAGE = System.getProperty("harness.gateway.image", "jobmatch-api-gateway:harness");

    private static final int PORT = 8081;
    private static final int MANAGEMENT_PORT = 9090;

    private record Key(int applicationPort, Map<String, String> serviceUrls) {
    }

    private static final Map<Key, GenericContainer<?>> BY_ROUTES = new ConcurrentHashMap<>();

    private Gateway() {
    }

    /** The base URL a client uses for the application on this port. */
    public static String baseUrl(int applicationPort, Map<String, String> serviceUrls) {
        if (!ON) {
            return "http://localhost:" + applicationPort;
        }
        Key key = new Key(applicationPort, Map.copyOf(serviceUrls));
        GenericContainer<?> gateway = BY_ROUTES.computeIfAbsent(key, Gateway::start);
        return "http://" + gateway.getHost() + ":" + gateway.getMappedPort(PORT);
    }

    private static GenericContainer<?> start(Key key) {
        int applicationPort = key.applicationPort();
        Map<String, String> serviceUrls = key.serviceUrls();
        Testcontainers.exposeHostPorts(applicationPort);
        GenericContainer<?> gateway = new GenericContainer<>(DockerImageName.parse(IMAGE))
                // A local build, never a pull: an image by this name on a registry is not ours.
                .withImagePullPolicy(name -> false)
                .withEnv("BACKEND_URL", "http://host.testcontainers.internal:" + applicationPort)
                .withEnv("JOB_SERVICE_URL", insideContainer(Services.url(Services.JOB_SERVICE, applicationPort, serviceUrls)))
                // The suite logs in from one address far more than ten times a minute.
                .withEnv("RATE_LIMIT_AUTH_PER_MINUTE", "1000000")
                .withExposedPorts(PORT, MANAGEMENT_PORT)
                // Ready, not just listening. The gateway's port accepts requests before its proxy
                // has its header filters, and one arriving in between is answered 500: a login in
                // MatchRankingIT was, once the management server made that window longer (Day 38).
                .waitingFor(Wait.forHttp("/actuator/health/readiness").forPort(MANAGEMENT_PORT));
        try {
            gateway.start();
        } catch (RuntimeException e) {
            throw new IllegalStateException("harness.gateway is set, but the gateway did not start from " + IMAGE
                    + ". Build it first: docker build -t " + IMAGE + " services/api-gateway", e);
        }
        return gateway;
    }

    private static String insideContainer(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (!host.equals("localhost") && !host.equals("127.0.0.1")) {
            throw new IllegalArgumentException("Service URL host must be localhost or 127.0.0.1, but got: " + url);
        }
        Testcontainers.exposeHostPorts(uri.getPort());
        return "http://host.testcontainers.internal:" + uri.getPort();
    }
}
