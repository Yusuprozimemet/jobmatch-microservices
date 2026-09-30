package nl.hackyourfuture.project.backend.support;

import nl.hackyourfuture.project.backend.BackendApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

/**
 * Base class for every integration test: the application on a random port, a Postgres with
 * the app schema migrated and the mart seeded, and a clean slate before each test.
 *
 * <pre>
 * class JobSearchTest extends IntegrationTest {
 *     &#64;Test
 *     void searchesByCity() {
 *         TestUser user = aUser().create();
 *         aPosting().title("Rust Engineer").cities("delft").create();
 *
 *         ApiResponse response = authenticatedAs(user).get("/api/jobs?location=Delft");
 *
 *         assertThat(response.status()).isEqualTo(200);
 *     }
 * }
 * </pre>
 *
 * <p>Everything here talks to the application over HTTP and to the database over JDBC, and
 * never to an application bean. These tests are the safety net for the split, so they have to
 * keep passing when the beans behind an endpoint move into another service.
 */
@SpringBootTest(classes = BackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @LocalServerPort
    private int port;

    // Loading this class starts the container, so the mart schema exists before Flyway runs.
    @DynamicPropertySource
    static void useTestContainer(DynamicPropertyRegistry registry) {
        // Flyway logs in as the container's owner, as it does in compose; every module as its own
        // role, with its own schema as the search path (Day 11).
        registry.add("spring.flyway.url", PostgresContainer::jdbcUrl);
        registry.add("spring.flyway.user", () -> PostgresContainer.instance().getUsername());
        registry.add("spring.flyway.password", () -> PostgresContainer.instance().getPassword());
        for (String module : PostgresContainer.CONNECTED_SCHEMAS) {
            registry.add("app.datasource." + module + ".url", () -> PostgresContainer.jdbcUrl(module));
            registry.add("app.datasource." + module + ".username", () -> module + "_user");
            registry.add("app.datasource." + module + ".password", PostgresContainer::rolePassword);
        }
        // The application does not start without a signing key (Day 12), and none is committed.
        registry.add("app.jwt.private-key-file", () -> TestSigningKey.path().toString());
        // The service key (Day 39) is separate and required like the user key.
        registry.add("app.service-jwt.private-key-file", () -> TestSigningKey.servicePath().toString());
        // Register the test caller as a trusted issuer for /internal tests (Day 39, Day 17 list form).
        registry.add("app.internal.trusted-issuers[0].name", () -> TestServiceCaller.ISSUER);
        registry.add("app.internal.trusted-issuers[0].key-set-url", () -> TestServiceCaller.instance().jwksUrl());
        // job-service (Day 17). A supplier: its container is up before a context needs its key set.
        registry.add("app.internal.trusted-issuers[1].name", () -> "jobmatch-job-service");
        registry.add("app.internal.trusted-issuers[1].key-set-url", () -> JobService.baseUrl() + "/.well-known/service-jwks.json");
        // matching-service (Day 21). A supplier: its key server is up before a context needs it.
        registry.add("app.internal.trusted-issuers[2].name", () -> MatchingServiceKey.ISSUER);
        registry.add("app.internal.trusted-issuers[2].key-set-url", () -> MatchingServiceKey.jwksUrl());
        // The monolith's postings clients call the container; application-test.yaml maps
        // app.internal.jobs-url to it, so a failure test's own app.internal.jobs-url still wins.
        registry.add("harness.job-service-url", JobService::baseUrl);
    }

    @BeforeEach
    void resetDatabase() {
        TestDatabase.reset();
        // job-service's counts calls go through one relay; point it at this test's context.
        JobService.relayTo(port);
    }

    /**
     * Where this class sends a service's paths instead of that service's default, by service name
     * ({@link Services}). Empty: every path goes where the rest of the run sends it.
     */
    protected Map<String, String> serviceUrls() {
        return Map.of();
    }

    /** Direct database access, for setting up state a builder does not cover and for assertions. */
    protected JdbcClient jdbc() {
        return TestDatabase.jdbc();
    }

    /** Direct database access to the jobs database, where the mart is. */
    protected JdbcClient jobsJdbc() {
        return TestDatabase.jobsJdbc();
    }

    protected UserBuilder aUser() {
        return new UserBuilder(jdbc());
    }

    protected ProfileBuilder aProfile() {
        return new ProfileBuilder(jdbc());
    }

    protected PostingBuilder aPosting() {
        return new PostingBuilder(jobsJdbc());
    }

    /**
     * A client with no cookies - what a logged-out visitor gets. Through the gateway when the run
     * asks for it ({@link Gateway}), which routes each request to the service that owns its path;
     * directly, the harness's clients route.
     */
    protected ApiClient anonymous() {
        if (Gateway.ON) {
            return ApiClient.at(Gateway.baseUrl(port, serviceUrls()));
        } else {
            return ApiClient.routed("http://localhost:" + port,
                    path -> Services.baseUrlFor(path, port, serviceUrls()));
        }
    }

    /** A client that always talks to the application itself, gateway or not. */
    protected ApiClient direct() {
        return ApiClient.onPort(port);
    }

    /**
     * A client inside the network: each path to the service that owns it ({@link Services}), never
     * through the gateway, which does not route /internal. For calls between services.
     */
    protected ApiClient inNetwork() {
        return ApiClient.routed("http://localhost:" + port, path -> Services.baseUrlFor(path, port, serviceUrls()));
    }

    /**
     * A client that has logged in as this user through {@code POST /api/auth/login}.
     *
     * <p>The real login endpoint, not a forged security context, so the test exercises whatever
     * the application currently issues - a session cookie today, a JWT cookie after Phase 2 -
     * and does not have to be rewritten when that changes.
     */
    protected ApiClient authenticatedAs(TestUser user) {
        if (user.password() == null) {
            throw new IllegalArgumentException(
                    "User " + user.email() + " has no password (Google-only account), so it cannot log in");
        }
        ApiClient client = anonymous();
        ApiResponse response = client.post("/api/auth/login",
                Map.of("email", user.email(), "password", user.password()));
        if (response.status() != 200) {
            throw new IllegalStateException("Could not log in as " + user.email()
                    + ": login returned " + response.status() + " " + response.body());
        }
        return client;
    }
}
