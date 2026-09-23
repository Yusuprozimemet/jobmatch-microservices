package nl.hackyourfuture.project.backend.matching;

import nl.hackyourfuture.project.backend.shared.jobs.ShortlistedPosting;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Asks a language model to score a shortlist of postings against a candidate's skills.
// Speaks the OpenAI chat-completions format, so switching provider is just config, not code.
// Never throws - any failure returns an empty map and the caller falls back to SQL ordering.
@Slf4j
@Component
public class MatchScorer {

    private static final int MAX_REASON_LENGTH = 120;

    // Bump this whenever buildPrompt's output changes, so old and new scores don't mix.
    private static final String PROMPT_VERSION = "v1";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final String apiKey;
    private final String model;
    private final String reasoningEffort;

    public MatchScorer(
            @Value("${app.llm.api-key:}") String apiKey,
            @Value("${app.llm.base-url}") String baseUrl,
            @Value("${app.llm.model}") String model,
            @Value("${app.llm.timeout-seconds:20}") int timeoutSeconds,
            @Value("${app.llm.reasoning-effort:}") String reasoningEffort
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(timeoutSeconds))
                .build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory requestFactory(int timeoutSeconds) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return factory;
    }

    @jakarta.annotation.PostConstruct
    void logConfiguration() {
        if (isEnabled()) {
            log.info("Job match AI scoring enabled (model {})", model);
        } else {
            log.warn("LLM_API_KEY is not set: /api/jobs/top-matches will rank by skill overlap only.");
        }
    }

    private boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    // Identifies which model + prompt produced a score, so changing either invalidates old scores.
    public String version() {
        return model + "/" + PROMPT_VERSION;
    }

    // Scores each posting 0-100, keyed by posting id. Missing entries are normal - the
    // caller falls back for whatever's absent.
    public Map<String, Score> score(List<String> candidateSkills, List<ShortlistedPosting> jobs) {
        if (!isEnabled() || jobs.isEmpty()) {
            return Map.of();
        }
        try {
            String content = callModel(buildPrompt(candidateSkills, jobs));
            return parseScores(content, jobs);
        } catch (RestClientResponseException e) {
            // Usually a bad request (e.g. an unsupported field), not the model being down -
            // log the response body so it's clear which one it was.
            log.warn("LLM scoring rejected by the provider ({}), falling back to skill-overlap order: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return Map.of();
        } catch (Exception e) {
            // Catch anything - any failure here should just fall back, not break the request.
            log.warn("LLM scoring unavailable, falling back to skill-overlap order: {}", e.getMessage());
            return Map.of();
        }
    }

    // Short ids keep the prompt small and less likely to be echoed back wrong.
    private String buildPrompt(List<String> candidateSkills, List<ShortlistedPosting> jobs) {
        StringBuilder prompt = new StringBuilder()
                .append("Candidate skills: ").append(String.join(", ", candidateSkills)).append("\n\n")
                .append("Score how well each job matches the candidate, 0-100. Treat equivalent ")
                .append("technologies as matches (postgres/postgresql, react/reactjs, k8s/kubernetes). ")
                .append("Take the seniority in the title into account.\n\nJobs:\n");

        for (ShortlistedPosting job : jobs) {
            prompt.append(shortId(job.postingId())).append(" | ")
                    .append(job.title()).append(" | ")
                    .append(String.join(", ", job.jobSkills())).append("\n");
        }

        return prompt.append("\nReturn ONLY a JSON array, one entry per job, no other text:\n")
                .append("[{\"id\":\"...\",\"score\":0-100,\"reason\":\"under 12 words\"}]")
                .toString();
    }

    private String callModel(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0);
        // Only send this if it's set - not every provider accepts it, some reject it outright.
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            body.put("reasoning_effort", reasoningEffort);
        }
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        // Parsed as a plain String, not a typed class - the response shape differs per provider.
        String raw = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);

        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("model returned an empty body");
        }

        JsonNode response = objectMapper.readTree(raw);
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("model returned no choices: " + raw.substring(0, Math.min(200, raw.length())));
        }
        return choices.get(0).path("message").path("content").asString("");
    }

    private Map<String, Score> parseScores(String content, List<ShortlistedPosting> jobs) throws Exception {
        // The model often wraps the JSON in extra text or a code fence, so just grab
        // everything between the first [ and the last ].
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("no JSON array in model reply");
        }

        Map<String, String> byShortId = new HashMap<>();
        for (ShortlistedPosting job : jobs) {
            byShortId.put(shortId(job.postingId()), job.postingId());
        }

        Map<String, Score> scores = new HashMap<>();
        for (JsonNode node : objectMapper.readTree(content.substring(start, end + 1))) {
            // Skip any id the model made up.
            String postingId = byShortId.get(node.path("id").asString(""));
            if (postingId == null) {
                continue;
            }
            scores.put(postingId, new Score(
                    clamp(node.path("score").asInt(0)),
                    truncate(node.path("reason").asString(null))
            ));
        }
        return scores;
    }

    private static String shortId(String postingId) {
        return postingId.length() > 8 ? postingId.substring(0, 8) : postingId;
    }

    private static int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }

    // What the model thought of one posting.
    public record Score(int value, String reason) {}
}
