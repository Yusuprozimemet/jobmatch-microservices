package nl.hackyourfuture.project.backend.support;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for the language model: one {@code POST /chat/completions} endpoint speaking the
 * OpenAI chat-completions shape that {@code MatchScorer} is written against.
 *
 * <p>A real server rather than a mocked bean, because the day's subject <em>is</em> the round
 * trip: the request body, the {@code choices[0].message.content} envelope, and the
 * prose-wrapped JSON array {@code MatchScorer} digs out of it. A stubbed bean returning a
 * {@code Map<String, Score>} would skip all three, which is where that class actually breaks.
 *
 * <p>The scores are given <strong>in prompt order</strong>: the stub reads back the job ids the
 * application put in the prompt and answers about those. That is what a model does, and it
 * keeps the tests from having to restate {@code MatchScorer}'s id-shortening rule.
 *
 * <p>{@link #hang()} holds calls, for what a model that never answers does to the rest of the
 * application; {@link #traceparents()} is what a trace looks like from the model's side.
 *
 * <p>One instance per JVM, mutated by the {@code will*} methods. Tests in a class run one at a
 * time, so that is safe here; running these classes in parallel would not be.
 */
public final class StubLlm {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final StubLlm INSTANCE = new StubLlm();

    private final HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> traceparents = new CopyOnWriteArrayList<>();

    private volatile int[] scoresInPromptOrder = new int[0];
    private volatile String rawContent;
    private volatile int failWithStatus;
    private volatile String lastPrompt;
    private final AtomicInteger hanging = new AtomicInteger();
    private volatile CountDownLatch release;

    private StubLlm() {
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // Virtual threads: a hung call must not hold up the others.
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the stub language model", e);
        }
        server.createContext("/chat/completions", this::handleCompletion);
        server.start();
    }

    public static StubLlm instance() {
        return INSTANCE;
    }

    /** Point {@code app.llm.base-url} here. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Call from {@code @BeforeEach}: the instance outlives any one test. */
    public void reset() {
        calls.set(0);
        scoresInPromptOrder = new int[0];
        rawContent = null;
        failWithStatus = 0;
        lastPrompt = null;
        traceparents.clear();
        CountDownLatch hung = release;
        release = null;
        if (hung != null) {
            hung.countDown();
        }
    }

    /** How many times the application actually called a model. Zero means it never tried. */
    public int callCount() {
        return calls.get();
    }

    /** The prompt of the most recent call, for asserting what was sent. */
    public String lastPrompt() {
        return lastPrompt;
    }

    /** Score the jobs in the order the prompt listed them. Extra jobs go unscored. */
    public void willScoreInPromptOrder(int... scores) {
        this.scoresInPromptOrder = scores.clone();
        this.rawContent = null;
        this.failWithStatus = 0;
    }

    /** Answer with this exact assistant message, for the shapes a real model produces. */
    public void willReplyWith(String content) {
        this.rawContent = content;
        this.failWithStatus = 0;
    }

    /** Answer with an HTTP error, the way a provider rejects or falls over. */
    public void willFail(int status) {
        this.failWithStatus = status;
    }

    /**
     * Hold every call until {@link #reset()}, then answer 503: a provider that accepts the
     * connection and never answers. Held at most 90 s, so a test that forgets to release cannot
     * hold the run; longer than any read timeout a test breaks the application with.
     */
    public void hang() {
        this.release = new CountDownLatch(1);
    }

    /** Wait until at least {@code calls} calls are being held, up to 10 s. */
    public void awaitHanging(int calls) {
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

    /** The {@code traceparent} header of each call since the last reset, in arrival order. */
    public List<String> traceparents() {
        return traceparents;
    }

    private void handleCompletion(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        String traceparent = exchange.getRequestHeaders().getFirst("traceparent");
        if (traceparent != null) {
            traceparents.add(traceparent);
        }

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

        String prompt = readPrompt(exchange);
        lastPrompt = prompt;

        if (failWithStatus != 0) {
            respond(exchange, failWithStatus, "{\"error\":{\"message\":\"stubbed failure\"}}");
            return;
        }

        String content = rawContent != null ? rawContent : scoreJobsIn(prompt);
        respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":%s}}]}"
                .formatted(JSON.writeValueAsString(content)));
    }

    private String readPrompt(HttpExchange exchange) throws IOException {
        JsonNode body = JSON.readTree(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        return body.path("messages").path(0).path("content").asString("");
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

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
