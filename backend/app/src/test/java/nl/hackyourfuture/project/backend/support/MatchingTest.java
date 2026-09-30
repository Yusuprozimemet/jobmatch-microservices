package nl.hackyourfuture.project.backend.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for the top-matches tests: the matching-service container talking to
 * {@link StubLlm} (wired in MatchingService) instead of a language model.
 * Resets the stub before and after each test.
 */
public abstract class MatchingTest extends IntegrationTest {

    /** Matching only shortlists postings in the profile's city, so the tests own one. */
    protected static final String CITY = "testville";

    @BeforeEach
    void resetTheModel() {
        StubLlm.instance().reset();
    }

    @AfterEach
    void releaseTheModel() {
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
