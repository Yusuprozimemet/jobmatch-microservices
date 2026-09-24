package nl.hackyourfuture.project.gateway;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<String, String> answerHeaders = new ConcurrentHashMap<>();
    private volatile long answerAfterMillis;

    RecordingUpstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                Thread.sleep(answerAfterMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders(), body));
            byte[] answer = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            answerHeaders.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
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
        answerHeaders.clear();
    }

    /** Answers only after this long from now on, as a backend that has stalled would. */
    void answerAfter(java.time.Duration delay) {
        answerAfterMillis = delay.toMillis();
    }

    /** Adds this header to every answer from now on, as a service behind the gateway might. */
    void answerWith(String name, String value) {
        answerHeaders.put(name, value);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
