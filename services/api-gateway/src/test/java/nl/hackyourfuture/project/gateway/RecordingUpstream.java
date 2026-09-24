package nl.hackyourfuture.project.gateway;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the backend that records what reaches it and answers 200. A check made through
 * the real backend cannot tell the gateway's answer from the backend's own; this one can, because
 * a request the gateway refuses never appears here.
 */
final class RecordingUpstream implements AutoCloseable {

    /** One request as the upstream received it. */
    record Received(String method, String pathAndQuery, Headers headers, String body) {
    }

    static final String BODY = "{\"from\":\"upstream\"}";

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();

    RecordingUpstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders(), body));
            byte[] answer = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    void clear() {
        received.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
