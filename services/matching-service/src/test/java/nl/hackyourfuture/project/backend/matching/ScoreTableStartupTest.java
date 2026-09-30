package nl.hackyourfuture.project.backend.matching;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Table creation at startup outlasts a store that is slow or not up yet (#240). The client is the
 * service's own, with the request path's 500 ms call timeout; the store is a fake that answers
 * DynamoDB's JSON protocol for an existing table with TTL on, so the only calls are the two
 * describes.
 */
class ScoreTableStartupTest {

    private static final String TABLE = "job_match_scores";

    private HttpServer store;
    private DynamoDbClient client;

    @BeforeEach
    void dummyCredentials() {
        System.setProperty("aws.accessKeyId", "local");
        System.setProperty("aws.secretAccessKey", "local");
    }

    @AfterEach
    void stop() {
        if (store != null) {
            store.stop(0);
        }
        if (client != null) {
            client.close();
        }
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    /**
     * Every answer after 1 s, twice the request path's timeout. Repeating the first call would not
     * help: each call needs longer than 500 ms.
     */
    @Test
    void aSlowStoreDoesNotStopStartup() throws IOException {
        int port = freePort();
        store = startStore(port, Duration.ofSeconds(1));
        client = client(port);

        assertThatCode(() -> new ScoreStoreConfig.ScoreTableCreator(client, TABLE).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    /** A store that starts listening after 6 s, longer than one startup call's 5 s. */
    @Test
    void aStoreThatStartsLateIsWaitedFor() throws Exception {
        int port = freePort();
        client = client(port);

        CompletableFuture<Void> startup = CompletableFuture.runAsync(() -> {
            try {
                new ScoreStoreConfig.ScoreTableCreator(client, TABLE).afterPropertiesSet();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(6_000);
        store = startStore(port, Duration.ZERO);

        assertThat(startup).succeedsWithin(Duration.ofSeconds(20));
    }

    private DynamoDbClient client(int port) {
        return new ScoreStoreConfig().scoresDynamoDbClient("http://127.0.0.1:" + port, "eu-west-1",
                Duration.ofMillis(500));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static HttpServer startStore(int port, Duration delay) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            String target = exchange.getRequestHeaders().getFirst("X-Amz-Target");
            String body;
            if (target.endsWith(".DescribeTable")) {
                body = "{\"Table\":{\"TableName\":\"" + TABLE + "\",\"TableStatus\":\"ACTIVE\"}}";
            } else if (target.endsWith(".DescribeTimeToLive")) {
                body = "{\"TimeToLiveDescription\":{\"TimeToLiveStatus\":\"ENABLED\",\"AttributeName\":\"ttl\"}}";
            } else {
                body = "{}";
            }
            sleep(delay);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/x-amz-json-1.0");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
