package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The posting details a saved job carries, and what happens when the posting is gone.
 *
 * <p>Today this is a {@code LEFT JOIN} from {@code saved_jobs} into {@code analytics.fct_postings}
 * — one of the two cross-schema joins `plan.md` says must die. Day 09 replaces it with a
 * {@code PostingLookup} interface and Phase 3 puts HTTP behind that, so every assertion here is
 * about the fields the user sees, never about a join. <strong>This file must survive Day 09
 * unedited.</strong>
 */
class SavedJobHydrationIT extends IntegrationTest {

    @Test
    void carriesThePostingsDetailsAlongsideTheSavedState() {
        ApiClient client = authenticatedAs(aUser().create());
        client.post("/api/saved-jobs", Map.of("postingId", "seed-0001"));

        ApiResponse list = client.get("/api/saved-jobs");

        assertThat(list.at("/content/0/postingId").asString()).isEqualTo("seed-0001");
        assertThat(list.at("/content/0/jobState").asString()).isEqualTo("SAVED");
        assertThat(list.at("/content/0/title").asString()).isEqualTo("Backend Developer");
        assertThat(list.at("/content/0/companyName").asString()).isEqualTo("Nedap");
        assertThat(list.at("/content/0/workMode").asString()).isEqualTo("onsite");
        assertThat(list.at("/content/0/isRemote").asBoolean()).isFalse();
        assertThat(list.at("/content/0/employmentType").asString()).isEqualTo("full_time");
        assertThat(list.at("/content/0/category").asString()).isEqualTo("software_engineering");
        assertThat(list.at("/content/0/source").asString()).isEqualTo("seed");
        assertThat(list.at("/content/0/freshnessClass").asString()).isEqualTo("fresh");
        assertThat(list.at("/content/0/ageDays").asInt()).isEqualTo(1);
        assertThat(list.at("/content/0/postedDate").asString()).isNotBlank();
        assertThat(strings(list.at("/content/0/skills")))
                .containsExactly("java", "spring", "sql", "postgresql");
    }

    /**
     * The case a mart republish creates: the posting is dropped, the save is not. It must keep
     * listing — a user who applied through a job that has since closed still needs the row in
     * their tracker. Everything the mart owned comes back null.
     */
    @Test
    void keepsListingASavedJobWhosePostingHasLeftTheMart() {
        ApiClient client = authenticatedAs(aUser().create());
        client.post("/api/saved-jobs", Map.of("postingId", "republished-away"));

        ApiResponse list = client.get("/api/saved-jobs");

        assertThat(list.at("/totalElements").asLong()).isEqualTo(1);
        assertThat(list.at("/content/0/postingId").asString()).isEqualTo("republished-away");
        assertThat(list.at("/content/0/jobState").asString()).isEqualTo("SAVED");
        assertThat(list.at("/content/0/title").isNull()).isTrue();
        assertThat(list.at("/content/0/companyName").isNull()).isTrue();
        assertThat(list.at("/content/0/location").isNull()).isTrue();
        assertThat(list.at("/content/0/postedDate").isNull()).isTrue();
        assertThat(list.at("/content/0/ageDays").isNull()).isTrue();
        // An empty list rather than null, so the frontend renders it without a check.
        assertThat(strings(list.at("/content/0/skills"))).isEmpty();
    }

    // A row with no posting has no posted date, and the list is newest first, so it sorts last.
    @Test
    void sortsAVanishedPostingAfterTheOnesStillInTheMart() {
        ApiClient client = authenticatedAs(aUser().create());
        client.post("/api/saved-jobs", Map.of("postingId", "republished-away"));
        client.post("/api/saved-jobs", Map.of("postingId", "seed-0001"));

        assertThat(postingIds(client.get("/api/saved-jobs")))
                .containsExactly("seed-0001", "republished-away");
    }

    // The state still moves, and the row still counts, with nothing behind it in the mart.
    @Test
    void aVanishedPostingCanStillBeTrackedAndRemoved() {
        ApiClient client = authenticatedAs(aUser().create());
        client.post("/api/saved-jobs", Map.of("postingId", "republished-away"));

        assertThat(client.patch("/api/saved-jobs/republished-away", Map.of("newState", "APPLIED"))
                .status()).isEqualTo(200);
        assertThat(client.get("/api/saved-jobs/stats").at("/APPLIED").asInt()).isEqualTo(1);
        assertThat(client.delete("/api/saved-jobs/republished-away").status()).isEqualTo(204);
    }

    @Test
    void reportsAnEmptySkillsListForAPostingThatHasNone() {
        ApiClient client = authenticatedAs(aUser().create());
        // seed-0023 carries an empty skills array.
        client.post("/api/saved-jobs", Map.of("postingId", "seed-0023"));

        ApiResponse list = client.get("/api/saved-jobs");

        assertThat(list.at("/content/0/title").asString()).isEqualTo("Graduate Software Engineer");
        assertThat(strings(list.at("/content/0/skills"))).isEmpty();
    }

    // A closed posting is still hydrated: closing a job does not erase the application.
    @Test
    void hydratesAPostingThatHasClosed() {
        ApiClient client = authenticatedAs(aUser().create());
        client.post("/api/saved-jobs", Map.of("postingId", "seed-0024"));

        ApiResponse list = client.get("/api/saved-jobs");

        assertThat(list.at("/content/0/title").asString()).isEqualTo("Legacy Systems Engineer");
        assertThat(list.at("/content/0/companyName").asString()).isEqualTo("Ordina");
    }

    @Test
    void agreesWithJobSearchAboutTitleAndCompany() {
        ApiClient client = authenticatedAs(aUser().create());
        client.post("/api/saved-jobs", Map.of("postingId", "seed-0001"));

        ApiResponse saved = client.get("/api/saved-jobs");
        ApiResponse detail = anonymous().get("/api/jobs/seed-0001");

        assertThat(saved.at("/content/0/title").asString())
                .isEqualTo(detail.at("/title").asString());
        assertThat(saved.at("/content/0/companyName").asString())
                .isEqualTo(detail.at("/companyName").asString());
    }

    /**
     * Where the two views disagree, and deliberately asserted rather than left to be found
     * during Day 09's rewrite.
     *
     * <p>A saved job reads {@code fct_postings.location}, the free-text column. Job search and
     * job detail read the normalised city bridge instead, which is initial-capped and drops
     * countries. {@code seed-0021} carries both a city and a country, so the same posting
     * reports two different places depending on which screen the user is looking at.
     */
    @Test
    void showsTheFreeTextLocationWhichCanNameAPlaceSearchWouldNot() {
        ApiClient client = authenticatedAs(aUser().create());
        client.post("/api/saved-jobs", Map.of("postingId", "seed-0021"));

        String inSavedJobs = client.get("/api/saved-jobs").at("/content/0/location").asString();
        String inJobDetail = anonymous().get("/api/jobs/seed-0021").at("/location").asString();

        assertThat(inSavedJobs).isEqualTo("Netherlands, Utrecht");
        assertThat(inJobDetail).isEqualTo("Utrecht");
        assertThat(inSavedJobs).isNotEqualTo(inJobDetail);
    }

    // Where there is no country in the way, the two agree - so the difference above is the
    // country, not two unrelated ways of writing a place.
    @Test
    void agreesWithJobDetailOnAPostingWithNoCountryInItsCities() {
        ApiClient client = authenticatedAs(aUser().create());
        // seed-0013 is advertised in two cities and no country.
        client.post("/api/saved-jobs", Map.of("postingId", "seed-0013"));

        assertThat(client.get("/api/saved-jobs").at("/content/0/location").asString())
                .isEqualTo(anonymous().get("/api/jobs/seed-0013").at("/location").asString())
                .isEqualTo("Amsterdam, Eindhoven");
    }

    private static List<String> postingIds(ApiResponse response) {
        List<String> ids = new ArrayList<>();
        for (JsonNode savedJob : response.at("/content")) {
            ids.add(savedJob.get("postingId").asString());
        }
        return ids;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode element : array) {
            values.add(element.asString());
        }
        return values;
    }
}
