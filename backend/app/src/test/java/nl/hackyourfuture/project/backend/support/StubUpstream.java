package nl.hackyourfuture.project.backend.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An upstream for Day 19's internal clients to fail against: a failure test points their URL
 * properties here, and each path answers, refuses with a status, or hangs, as the test sets it.
 * Counts the calls each path gets.
 *
 * <p>Handlers run on virtual threads of their own, not on the single default thread
 * {@link StubLlm} uses, on which one hanging request would hold up every later one. A hang lasts
 * until {@link #reset()}, at most 30 s, and then the connection closes with no response.
 *
 * <p>One instance per JVM; tests in a class run one at a time, so that is safe here.
 */
public final class StubUpstream {

    private static final StubUpstream INSTANCE = new StubUpstream();
    private static final Answer NOT_FOUND = new Answer(404, "");

    private final HttpServer server;
    private final Map<String, Mode> modes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private volatile CountDownLatch released = new CountDownLatch(1);

    private sealed interface Mode permits Answer, Hang {
    }

    private record Answer(int status, String body) implements Mode {
    }

    private record Hang() implements Mode {
    }

    private StubUpstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the stub upstream", e);
        }
    }

    public static StubUpstream instance() {
        return INSTANCE;
    }

    /** What to set {@code app.internal.jobs-url} or {@code app.internal.applications-url} to. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Call from {@code @BeforeEach}: forgets every mode and count, and releases every hang. */
    public void reset() {
        CountDownLatch hung = released;
        released = new CountDownLatch(1);
        hung.countDown();
        modes.clear();
        calls.clear();
    }

    public void answer(String path, int status, String json) {
        modes.put(path, new Answer(status, json));
    }

    /** A status with no body: 503 for an outage, 400 for a bug on the caller's side. */
    public void refuse(String path, int status) {
        modes.put(path, new Answer(status, ""));
    }

    public void hang(String path) {
        modes.put(path, new Hang());
    }

    public int calls(String path) {
        AtomicInteger count = calls.get(path);
        return count == null ? 0 : count.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        calls.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
        if (modes.getOrDefault(path, NOT_FOUND) instanceof Answer(int status, String body)) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            return;
        }
        CountDownLatch hung = released;
        try {
            hung.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        exchange.close();
    }
}
