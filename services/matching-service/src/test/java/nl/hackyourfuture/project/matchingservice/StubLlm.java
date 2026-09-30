package nl.hackyourfuture.project.matchingservice;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for the language model, the harness's {@code StubLlm} cut to what the tests moving
 * here use: {@code POST /chat/completions} in the OpenAI shape {@code MatchScorer} reads, scoring
 * the jobs in the order the prompt lists them. One instance per JVM; tests in a class run one at
 * a time, so that is safe here.
 */
final class StubLlm {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final StubLlm INSTANCE = new StubLlm();

    private final HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger hanging = new AtomicInteger();

    private volatile int[] scoresInPromptOrder = new int[0];
    private volatile String lastPrompt;
    private volatile CountDownLatch release;

    private StubLlm() {
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the stub language model", e);
        }
        server.createContext("/chat/completions", this::handleCompletion);
        server.start();
    }

    static StubLlm instance() {
        return INSTANCE;
    }

    /** Point {@code app.llm.base-url} here. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Call from {@code @BeforeEach}: the instance outlives any one test. */
    void reset() {
        calls.set(0);
        scoresInPromptOrder = new int[0];
        lastPrompt = null;
        CountDownLatch hung = release;
        release = null;
        if (hung != null) {
            hung.countDown();
        }
    }

    /** How many times the application actually called a model. Zero means it never tried. */
    int callCount() {
        return calls.get();
    }

    /** The prompt of the most recent call, for asserting what was sent. */
    String lastPrompt() {
        return lastPrompt;
    }

    /** Score the jobs in the order the prompt listed them. Extra jobs go unscored. */
    void willScoreInPromptOrder(int... scores) {
        this.scoresInPromptOrder = scores.clone();
    }

    /**
     * Hold every call until {@link #reset()}, then answer 503: a provider that accepts the
     * connection and never answers. Held at most 90 s, so a test that forgets to release cannot
     * hold the run; longer than any read timeout a test breaks the application with.
     */
    void hang() {
        this.release = new CountDownLatch(1);
    }

    /** Wait until at least {@code calls} calls are being held, up to 10 s. */
    void awaitHanging(int calls) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (hanging.get() < calls && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        if (hanging.get() < calls) {
            throw new AssertionError("Expected " + calls + " calls held by the model, found " + hanging.get());
        }
    }

    private void handleCompletion(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();

        CountDownLatch hung = release;
        if (hung != null) {
            hanging.incrementAndGet();
            try {
                hung.await(90, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                hanging.decrementAndGet();
            }
            respond(exchange, 503, "{\"error\":{\"message\":\"stubbed hang\"}}");
            return;
        }

        JsonNode body = JSON.readTree(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String prompt = body.path("messages").path(0).path("content").asString("");
        lastPrompt = prompt;

        byte[] bytes = "{\"choices\":[{\"message\":{\"content\":%s}}]}"
                .formatted(JSON.writeValueAsString(scoreJobsIn(prompt))).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // The prompt lists one job per line as "id | title | skills", between "Jobs:" and the
    // closing instruction. Reading the ids back is what lets a test score by position.
    private String scoreJobsIn(String prompt) {
        List<String> ids = new ArrayList<>();
        boolean inJobs = false;
        for (String line : prompt.split("\n")) {
            if (line.startsWith("Jobs:")) {
                inJobs = true;
                continue;
            }
            if (!inJobs || line.isBlank()) {
                continue;
            }
            if (!line.contains(" | ")) {
                break;
            }
            ids.add(line.substring(0, line.indexOf(" | ")).trim());
        }

        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < ids.size() && i < scoresInPromptOrder.length; i++) {
            if (i > 0) {
                array.append(',');
            }
            array.append("{\"id\":\"").append(ids.get(i))
                    .append("\",\"score\":").append(scoresInPromptOrder[i])
                    .append(",\"reason\":\"stubbed reason for ").append(ids.get(i)).append("\"}");
        }
        return array.append(']').toString();
    }
}
