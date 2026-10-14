package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/jobs/{id}} — the wire contract for a single posting.
 *
 * <p>The detail response carries every mart column the frontend shows, so it is the widest
 * surface Day 20 has to reproduce when the postings move to job-service's own database.
 */
class JobDetailIT extends IntegrationTest {

    @Test
    void returnsTheWholePosting() {
        ApiResponse response = anonymous().get("/api/jobs/seed-0001");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/postingId").asString()).isEqualTo("seed-0001");
        assertThat(response.at("/title").asString()).isEqualTo("Backend Developer");
        assertThat(response.at("/companyName").asString()).isEqualTo("Nedap");
        assertThat(response.at("/location").asString()).isEqualTo("Amsterdam");
        assertThat(response.at("/workMode").asString()).isEqualTo("onsite");
        assertThat(response.at("/isRemote").asBoolean()).isFalse();
        assertThat(skills(response)).containsExactly("java", "postgresql", "spring", "sql");
        assertThat(response.at("/employmentType").asString()).isEqualTo("full_time");
        assertThat(response.at("/category").asString()).isEqualTo("software_engineering");
        assertThat(response.at("/experienceLevel").asString()).isEqualTo("medior");
        assertThat(response.at("/educationLevel").asString()).isEqualTo("bachelor");
        assertThat(response.at("/salaryMin").asDouble()).isEqualTo(4500.0);
        assertThat(response.at("/salaryMax").asDouble()).isEqualTo(6000.0);
        assertThat(response.at("/salaryCurrency").asString()).isEqualTo("EUR");
        assertThat(response.at("/salaryPeriod").asString()).isEqualTo("month");
        assertThat(response.at("/status").asString()).isEqualTo("open");
        assertThat(response.at("/source").asString()).isEqualTo("seed");
        assertThat(response.at("/sourceUrl").asString()).isEqualTo("https://example.test/jobs/seed-0001");
        assertThat(response.at("/description").asString()).isNotBlank();
        assertThat(response.at("/postedDate").asString()).isNotBlank();
        assertThat(response.at("/freshnessClass").asString()).isEqualTo("fresh");
        assertThat(response.at("/ageDays").asInt()).isEqualTo(1);
    }

    @Test
    void returnsNotFoundForAPostingThatDoesNotExist() {
        ApiResponse response = anonymous().get("/api/jobs/no-such-posting");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.at("/detail").asString()).isEqualTo("Job posting not found");
    }

    @Test
    void joinsEveryCityOfAPostingIntoOneLocation() {
        // seed-0013 is advertised in two cities.
        ApiResponse response = anonymous().get("/api/jobs/seed-0013");

        assertThat(response.at("/location").asString()).isEqualTo("Amsterdam, Eindhoven");
    }

    // The city bridge carries countries too, and they are not places a user can filter on,
    // so they are not shown as ones either.
    @Test
    void leavesACountryOutOfTheLocation() {
        // seed-0021 carries both "utrecht" and "netherlands" in the city bridge.
        ApiResponse response = anonymous().get("/api/jobs/seed-0021");

        assertThat(response.at("/location").asString()).isEqualTo("Utrecht");
    }

    // An empty list rather than null: the frontend maps over it without a check.
    @Test
    void returnsAnEmptySkillsListForAPostingWithNoSkills() {
        ApiResponse response = anonymous().get("/api/jobs/seed-0023");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/skills").isNull()).isFalse();
        assertThat(skills(response)).isEmpty();
    }

    // A closed posting is still readable - a saved job must not 404 once it closes - and
    // says so in its status.
    @Test
    void returnsAClosedPostingWithItsStatus() {
        ApiResponse response = anonymous().get("/api/jobs/seed-0024");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/status").asString()).isEqualTo("closed");
    }

    private static List<String> skills(ApiResponse response) {
        List<String> values = new ArrayList<>();
        for (JsonNode skill : response.at("/skills")) {
            values.add(skill.asString());
        }
        return values;
    }
}
