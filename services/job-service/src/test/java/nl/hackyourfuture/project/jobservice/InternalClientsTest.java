package nl.hackyourfuture.project.jobservice;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import nl.hackyourfuture.project.backend.shared.internal.InternalClients;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * job-service's copy of {@code InternalClients} (Day 19) builds its clients as the monolith's does:
 * the base URL from a property read on the first call, job-service's own service token on every
 * request, and a read timeout that surfaces as the {@code ResourceAccessException} the breaker
 * records.
 */
class InternalClientsTest extends JobServiceTest {

    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final HttpServer UPSTREAM = upstream();

    @Autowired
    private InternalClients internalClients;

    @Autowired
    private ServiceSigningKey key;

    @DynamicPropertySource
    static void upstreamUrl(DynamicPropertyRegistry registry) {
        registry.add("test.internal.upstream-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
    }

    @Test
    void everyRequestCarriesJobServicesOwnToken() throws Exception {
        internalClients.forUrlProperty("test.internal.upstream-url").get()
                .get().uri("/answer").retrieve().toBodilessEntity();

        SignedJWT token = SignedJWT.parse(AUTHORIZATION.get().substring("Bearer ".length()));
        assertThat(token.verify(new RSASSAVerifier(key.publicJwk()))).isTrue();
        assertThat(token.getJWTClaimsSet().getIssuer()).isEqualTo(ServiceTokens.ISSUER);
    }

    @Test
    void aHangIsAResourceAccessExceptionWithinTheReadTimeout() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> internalClients.forUrlProperty("test.internal.upstream-url").get()
                .get().uri("/hang").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_500);
    }

    @Test
    void theSameClientIsReturnedEveryTime() {
        var supplier = internalClients.forUrlProperty("test.internal.upstream-url");

        assertThat(supplier.get()).isSameAs(supplier.get());
    }

    // Handlers on virtual threads, so a hanging request does not hold up the next test's.
    private static HttpServer upstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/answer", exchange -> {
                AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.createContext("/hang", exchange -> {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
