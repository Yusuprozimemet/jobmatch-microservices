package nl.hackyourfuture.project.backend.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import nl.hackyourfuture.project.backend.config.ServiceSigningKey;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * job-service in a container, one for the whole run (Day 17), started on first use from an image
 * built beforehand, as {@link Gateway} starts the gateway's. Ryuk removes it.
 *
 * <p>Its key is generated here, so a test can mint job-service's tokens. It trusts
 * {@code jobmatch-backend} at a key set served here from {@link TestSigningKey#servicePath()},
 * which every context signs with, and {@code jobmatch-test-caller} at {@link TestServiceCaller}'s.
 *
 * <p>Its counts calls go to a relay in this JVM: one container serves every context, so its
 * applications URL names the relay, and {@link IntegrationTest} points the relay at the running
 * test's context. The relay only forwards: a failing stub behind it would open job-service's one
 * breaker for the rest of the run.
 */
public final class JobService {

    /** The image, built from {@code services/job-service} before the run. */
    public static final String IMAGE = System.getProperty("harness.job-service.image", "jobmatch-job-service:harness");

    private static final int PORT = 8080;
    private static final int MANAGEMENT_PORT = 9090;
    private static final String AUDIENCE = "jobmatch-internal";
    private static final String ISSUER = "jobmatch-job-service";

    private static volatile GenericContainer<?> container;
    private static RSAKey key;
    private static HttpServer backendKeyServer;
    private static HttpServer relay;
    private static final AtomicInteger RELAY_TARGET_PORT = new AtomicInteger(-1);
    private static final HttpClient RELAY_CLIENT = HttpClient.newHttpClient();

    private JobService() {
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

    /** Where a client reaches job-service: its own base URL, container host and mapped port. */
    public static String baseUrl() {
        ensureStarted();
        return "http://" + container.getHost() + ":" + port();
    }

    /** The relay's address from this JVM, for tests of the relay itself. */
    public static String relayBaseUrl() {
        ensureStarted();
        return "http://127.0.0.1:" + relay.getAddress().getPort();
    }

    /** A token minted with job-service's own key, as if job-service itself had signed it. */
    public static String mintToken() {
        ensureStarted();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(ISSUER)
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        try {
            token.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign a token as job-service", e);
        }
        return token.serialize();
    }

    /**
     * Where the container's counts calls land until a test says otherwise: the context running the
     * current test. {@link IntegrationTest} calls this before every test.
     */
    public static void relayTo(int port) {
        ensureStarted();
        RELAY_TARGET_PORT.set(port);
    }

    private static synchronized void ensureStarted() {
        if (container != null) {
            return;
        }
        try {
            RSAKey generated = new RSAKeyGenerator(2048).generate();
            // The container publishes its key under the RFC 7638 thumbprint; a token minted with
            // any other key id matches nothing in its key set.
            String keyId = generated.toPublicJWK().computeThumbprint().toString();
            key = new RSAKey.Builder(generated).keyID(keyId).build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not generate job-service's test key", e);
        }
        Path pemFile;
        try {
            pemFile = TestSigningKey.write(TestSigningKey.pem(key.toPrivateKey().getEncoded()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not derive job-service's private key", e);
        }

        // Every context signs with the same service key, so one key-set server serves them all.
        RSAKey backendPublicKey = new ServiceSigningKey(TestSigningKey.servicePath().toString()).publicJwk();
        backendKeyServer = serveKeySet(backendPublicKey);
        relay = startRelay();

        int postgresPort = PostgresContainer.instance().getMappedPort(5432);
        Testcontainers.exposeHostPorts(postgresPort, backendKeyServer.getAddress().getPort(),
                relay.getAddress().getPort(), testCallerPort());

        GenericContainer<?> jobService = new GenericContainer<>(DockerImageName.parse(IMAGE))
                // A local build, never a pull: an image by this name on a registry is not ours.
                .withImagePullPolicy(name -> false)
                // Readable by the image's user 1000: a temp file on Linux is 0600, and the copy is root's.
                .withCopyFileToContainer(MountableFile.forHostPath(pemFile, 0444), "/run/keys/job-service.pem")
                .withEnv("SERVICE_JWT_PRIVATE_KEY_FILE", "/run/keys/job-service.pem")
                .withEnv("DB_HOST", "host.testcontainers.internal")
                .withEnv("DB_PORT", String.valueOf(postgresPort))
                .withEnv("DB_NAME", PostgresContainer.instance().getDatabaseName())
                .withEnv("DB_JOBS_USER", "jobs_user")
                .withEnv("DB_JOBS_PASSWORD", PostgresContainer.rolePassword())
                .withEnv("BACKEND_KEY_SET_URL",
                        "http://host.testcontainers.internal:" + backendKeyServer.getAddress().getPort() + "/service-jwks.json")
                .withEnv("APP_INTERNAL_TRUSTEDISSUERS_0_NAME", TestServiceCaller.ISSUER)
                .withEnv("APP_INTERNAL_TRUSTEDISSUERS_0_KEYSETURL",
                        "http://host.testcontainers.internal:" + testCallerPort() + "/.well-known/service-jwks.json")
                .withEnv("INTERNAL_APPLICATIONS_URL", "http://host.testcontainers.internal:" + relay.getAddress().getPort())
                .withExposedPorts(PORT, MANAGEMENT_PORT)
                .waitingFor(Wait.forHttp("/actuator/health/readiness").forPort(MANAGEMENT_PORT));
        try {
            jobService.start();
        } catch (RuntimeException e) {
            throw new IllegalStateException("job-service's harness image did not start from " + IMAGE
                    + ". Build it first: docker build -t " + IMAGE + " services/job-service", e);
        }
        container = jobService;
    }

    private static int testCallerPort() {
        return URI.create(TestServiceCaller.instance().jwksUrl()).getPort();
    }

    /** Serves one RSA public key at {@code /service-jwks.json}, forever, for the whole JVM. */
    private static HttpServer serveKeySet(RSAKey publicKey) {
        byte[] body = new JWKSet(publicKey).toString().getBytes(StandardCharsets.UTF_8);
        HttpServer server = newServer();
        server.createContext("/service-jwks.json", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    /** Forwards every request it gets to {@code http://localhost:<current target>}, unchanged. */
    private static HttpServer startRelay() {
        HttpServer server = newServer();
        server.createContext("/", exchange -> {
            int target = RELAY_TARGET_PORT.get();
            try {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                HttpRequest.Builder forward = HttpRequest.newBuilder(
                                URI.create("http://localhost:" + target + exchange.getRequestURI()))
                        .method(exchange.getRequestMethod(), requestBody.length == 0
                                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(requestBody));
                exchange.getRequestHeaders().forEach((name, values) -> {
                    if (isForwardable(name)) {
                        values.forEach(value -> forward.header(name, value));
                    }
                });
                HttpResponse<byte[]> response = RELAY_CLIENT.send(forward.build(), HttpResponse.BodyHandlers.ofByteArray());
                response.headers().map().forEach((name, values) -> {
                    if (isForwardable(name)) {
                        values.forEach(value -> exchange.getResponseHeaders().add(name, value));
                    }
                });
                exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response.body());
                }
            } catch (Exception e) {
                // Escaping, it would close the connection with no response; a 502 is readable.
                exchange.sendResponseHeaders(502, -1);
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    // Headers the JDK's HttpRequest.Builder refuses to set (it throws), and ones that would be
    // wrong once forwarded to another port. Everything else passes through, Authorization included.
    private static final Set<String> UNFORWARDABLE = Set.of(
            "content-length", "host", "connection", "transfer-encoding", "expect", "upgrade");

    private static boolean isForwardable(String name) {
        return !UNFORWARDABLE.contains(name.toLowerCase(Locale.ROOT));
    }

    private static HttpServer newServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
