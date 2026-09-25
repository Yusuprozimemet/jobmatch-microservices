package nl.hackyourfuture.project.backend.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Job-service's paths pointed at a recording stub in this JVM show which requests reach it:
 * directly, the harness's clients route; through the gateway ({@code -Dharness.gateway=true}),
 * the gateway does, with {@code JOB_SERVICE_URL} from the table. Its gateway is its own
 * (the route table is part of {@link Gateway}'s key).
 */
class ServiceRoutingIT extends IntegrationTest {

    private static final RecordingStub STUB = new RecordingStub();

    @Override
    protected Map<String, String> serviceUrls() {
        return Map.of(Services.JOB_SERVICE, STUB.url());
    }

    @BeforeEach
    void clearStub() {
        STUB.clear();
    }

    @Test
    void anonymousJobSearchReachesTheService() {
        ApiResponse response = anonymous().get("/api/jobs");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"from\":\"job-service stub\"}");
        assertThat(STUB.received()).hasSize(1);
        assertThat(STUB.received().get(0).pathAndQuery()).isEqualTo("/api/jobs");
    }

    @Test
    void anAuthenticatedJobDetailReachesTheServiceAsTheUser() {
        TestUser user = aUser().create();
        ApiResponse response = authenticatedAs(user).get("/api/jobs/seed-0001");

        assertThat(response.body()).isEqualTo("{\"from\":\"job-service stub\"}");
        assertThat(STUB.received()).hasSize(1);
        assertThat(STUB.received().get(0).pathAndQuery()).isEqualTo("/api/jobs/seed-0001");
        if (Gateway.ON) {
            assertThat(STUB.received().get(0).userId()).isEqualTo(user.id().toString());
        } else {
            assertThat(STUB.received().get(0).cookie()).contains("access_token=");
        }
    }

    @Test
    void topMatchesAndTheUsersOwnPathsStayOnTheMonolith() {
        ApiClient client = authenticatedAs(aUser().create());
        client.get("/api/jobs/top-matches");
        ApiResponse response = client.get("/api/users/me");

        assertThat(response.status()).isEqualTo(200);
        assertThat(STUB.received()).isEmpty();
    }

    private record Received(String pathAndQuery, String cookie, String userId) {
    }

    private static final class RecordingStub {
        private final HttpServer server;
        private final List<Received> receivedRequests = new CopyOnWriteArrayList<>();

        RecordingStub() {
            try {
                this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException("Could not start the recording stub", e);
            }
            server.createContext("/", this::handleRequest);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void clear() {
            receivedRequests.clear();
        }

        List<Received> received() {
            return List.copyOf(receivedRequests);
        }

        private void handleRequest(HttpExchange exchange) throws IOException {
            String pathAndQuery = exchange.getRequestURI().getPath() +
                    (exchange.getRequestURI().getQuery() != null ? "?" + exchange.getRequestURI().getQuery() : "");
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
            receivedRequests.add(new Received(pathAndQuery, cookie, userId));

            String body = "{\"from\":\"job-service stub\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
