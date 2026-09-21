package nl.hackyourfuture.project.backend.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for the {@code /api/jobs/top-matches} tests: the application talking to
 * {@link StubLlm} instead of a language model.
 *
 * <p>Both LLM properties are overridden, and both matter. The <strong>key</strong>, because
 * {@code application-test.yaml} blanks it and a blank key makes {@code MatchScorer} return
 * before it makes any call — every assertion about stubbing, failing or counting would pass
 * while testing nothing. The <strong>base URL</strong>, because the test profile does not set
 * one, so it otherwise falls through to {@code application.yaml}'s default, which is a live
 * provider.
 *
 * <p>Overriding these starts a second application context for the classes that extend this,
 * which is why the wiring is here rather than spread across them.
 */
@TestPropertySource(properties = {
        "app.llm.api-key=stub-key",
        "app.llm.model=stub-model",
        "app.llm.reasoning-effort="
})
public abstract class MatchingTest extends IntegrationTest {

    /** Matching only shortlists postings in the profile's city, so the tests own one. */
    protected static final String CITY = "testville";

    @DynamicPropertySource
    static void useStubLanguageModel(DynamicPropertyRegistry registry) {
        registry.add("app.llm.base-url", () -> StubLlm.instance().baseUrl());
    }

    @BeforeEach
    void resetTheModel() {
        StubLlm.instance().reset();
    }

    protected StubLlm model() {
        return StubLlm.instance();
    }

    /** A user whose profile is long enough to match on, in the tests' own city. */
    protected TestUser userWithProfile() {
        TestUser user = aUser().create();
        aProfile().forUser(user).preferredCity(CITY)
                .skills("java", "sql", "docker", "react", "go").create();
        return user;
    }

    protected void posting(String id, String title, String... skills) {
        aPosting().id(id).title(title).company("Company " + id).cities(CITY).skills(skills).create();
    }

    /** The posting ids of a top-matches response, in the order it returned them. */
    protected static List<String> postingIds(ApiResponse response) {
        List<String> ids = new ArrayList<>();
        for (JsonNode match : response.json()) {
            ids.add(match.get("postingId").asString());
        }
        return ids;
    }
}
