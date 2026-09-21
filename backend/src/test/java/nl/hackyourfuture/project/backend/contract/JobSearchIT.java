package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/jobs} — the wire contract for search, paging and the four filters the
 * endpoint accepts.
 *
 * <p>Counts are asserted against the seeded mart, which is re-created before every test, so a
 * number here is a statement about the fixture rather than about whatever the pipeline last
 * published. Day 20 moves this query onto job-service's own database; if the numbers below
 * still come out the same, the move kept its promise.
 */
class JobSearchIT extends IntegrationTest {

    /** Every posting in the seeded mart, closed ones included. */
    private static final int SEEDED_POSTINGS = 24;

    @Test
    void returnsEveryPostingWhenNothingIsFiltered() {
        ApiResponse response = anonymous().get("/api/jobs?size=100");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/totalElements").asLong()).isEqualTo(SEEDED_POSTINGS);
        assertThat(ids(response)).hasSize(SEEDED_POSTINGS);
    }

    @Test
    void returnsTwentyPerPageByDefault() {
        ApiResponse response = anonymous().get("/api/jobs");

        assertThat(response.at("/page").asInt()).isZero();
        assertThat(response.at("/size").asInt()).isEqualTo(20);
        assertThat(response.at("/totalPages").asInt()).isEqualTo(2);
        assertThat(ids(response)).hasSize(20);
    }

    @Test
    void ordersTheNewestPostingFirst() {
        ApiResponse response = anonymous().get("/api/jobs");

        // seed-0001 was posted yesterday; nothing in the fixture is newer.
        assertThat(ids(response)).first().isEqualTo("seed-0001");
    }

    @Test
    void pagesThroughTheResultsWithoutRepeatingOrLosingAPosting() {
        List<String> firstPage = ids(anonymous().get("/api/jobs?page=0&size=20"));
        List<String> secondPage = ids(anonymous().get("/api/jobs?page=1&size=20"));

        assertThat(secondPage).hasSize(4).doesNotContainAnyElementsOf(firstPage);
        assertThat(firstPage).hasSize(20);
    }

    @Test
    void pagingPastTheLastPageReturnsAnEmptyListRatherThanAnError() {
        ApiResponse response = anonymous().get("/api/jobs?page=99&size=20");

        assertThat(response.status()).isEqualTo(200);
        assertThat(ids(response)).isEmpty();
        // The total still describes the whole result set, not the empty page.
        assertThat(response.at("/totalElements").asLong()).isEqualTo(SEEDED_POSTINGS);
    }

    // A caller asking for the whole mart in one page gets the cap instead, and is told so.
    @Test
    void capsThePageSizeAtAHundred() {
        ApiResponse response = anonymous().get("/api/jobs?size=500");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/size").asInt()).isEqualTo(100);
    }

    @Test
    void rejectsANegativePageIndex() {
        assertThat(anonymous().get("/api/jobs?page=-1").status()).isEqualTo(400);
    }

    @Test
    void rejectsAPageSizeBelowOne() {
        assertThat(anonymous().get("/api/jobs?size=0").status()).isEqualTo(400);
    }

    @Test
    void filtersByCategory() {
        ApiResponse response = anonymous().get("/api/jobs?category=devops&size=100");

        assertThat(ids(response))
                .containsExactlyInAnyOrder("seed-0006", "seed-0012", "seed-0016", "seed-0019", "seed-0022");
        assertThat(response.at("/totalElements").asLong()).isEqualTo(5);
    }

    @Test
    void filtersByWorkMode() {
        ApiResponse response = anonymous().get("/api/jobs?workMode=remote&size=100");

        assertThat(ids(response))
                .containsExactlyInAnyOrder("seed-0008", "seed-0014", "seed-0018", "seed-0022");
    }

    @Test
    void filtersByCity() {
        ApiResponse response = anonymous().get("/api/jobs?location=Rotterdam&size=100");

        assertThat(ids(response)).containsExactlyInAnyOrder("seed-0004", "seed-0007");
    }

    @Test
    void matchesTheCityWhateverCaseItIsAskedFor() {
        assertThat(ids(anonymous().get("/api/jobs?location=rOTTERdam&size=100")))
                .containsExactlyInAnyOrder("seed-0004", "seed-0007");
    }

    // Equality, not a substring: '%Ede%' also matched Enschede, Medemblik and Sweden, which
    // is why the query moved off ILIKE. Both cities are created here rather than seeded, so
    // the assertion does not depend on which cities the fixture happens to carry.
    @Test
    void matchesAWholeCityRatherThanASubstringOfOne() {
        aPosting().id("in-ede").cities("ede").create();
        aPosting().id("in-enschede").cities("enschede").create();

        ApiResponse response = anonymous().get("/api/jobs?location=Ede&size=100");

        assertThat(ids(response)).containsExactly("in-ede");
    }

    // The city bridge carries countries and provinces as well as cities. They are excluded
    // everywhere, so a posting is not reachable by filtering on one.
    @Test
    void doesNotTreatACountryInTheCityBridgeAsACity() {
        // seed-0021 is in Utrecht and also carries a "netherlands" row in the city bridge.
        assertThat(ids(anonymous().get("/api/jobs?location=Utrecht&size=100")))
                .contains("seed-0021");

        assertThat(ids(anonymous().get("/api/jobs?location=Netherlands&size=100"))).isEmpty();
    }

    @Test
    void searchesFreeTextAcrossTitleCompanyCityAndSkill() {
        assertThat(ids(anonymous().get("/api/jobs?q=Analyst&size=100")))
                .as("title").containsExactly("seed-0004");
        assertThat(ids(anonymous().get("/api/jobs?q=Adyen&size=100")))
                .as("company").containsExactlyInAnyOrder("seed-0002", "seed-0016");
        assertThat(ids(anonymous().get("/api/jobs?q=rotterdam&size=100")))
                .as("city").containsExactlyInAnyOrder("seed-0004", "seed-0007");
        assertThat(ids(anonymous().get("/api/jobs?q=kubernetes&size=100")))
                .as("skill")
                .containsExactlyInAnyOrder("seed-0006", "seed-0012", "seed-0016", "seed-0022");
    }

    @Test
    void combinesTwoFilters() {
        ApiResponse response = anonymous().get("/api/jobs?category=devops&workMode=hybrid&size=100");

        assertThat(ids(response))
                .containsExactlyInAnyOrder("seed-0006", "seed-0012", "seed-0016");
    }

    @Test
    void combinesAFilterWithFreeText() {
        ApiResponse response = anonymous().get("/api/jobs?q=kubernetes&location=Eindhoven&size=100");

        assertThat(ids(response)).containsExactly("seed-0006");
    }

    @Test
    void returnsAnEmptyPageWhenNothingMatches() {
        ApiResponse response = anonymous().get("/api/jobs?category=underwater_basket_weaving");

        assertThat(response.status()).isEqualTo(200);
        assertThat(ids(response)).isEmpty();
        assertThat(response.at("/totalElements").asLong()).isZero();
        assertThat(response.at("/totalPages").asInt()).isZero();
    }

    // An option the filters endpoint offers that the search does not implement: Spring drops
    // a query parameter no @RequestParam declares, so it narrows nothing. Pinned as today's
    // behaviour - see the Day 03 spec, Phase 3 decides whether to add it or drop the option.
    @Test
    void ignoresAFilterItDoesNotImplement() {
        List<String> unfiltered = ids(anonymous().get("/api/jobs?size=100"));

        List<String> byEmploymentType =
                ids(anonymous().get("/api/jobs?employmentType=contract&size=100"));

        assertThat(byEmploymentType).isEqualTo(unfiltered);
    }

    // Search does not filter on status, while matching does exclude closed postings. Pinned
    // as it stands: seed-0024 is closed and a search still returns it.
    @Test
    void stillReturnsAClosedPosting() {
        assertThat(ids(anonymous().get("/api/jobs?q=Ordina&size=100"))).containsExactly("seed-0024");
    }

    @Test
    void isPublic() {
        assertThat(anonymous().get("/api/jobs").status()).isEqualTo(200);
    }

    private static List<String> ids(ApiResponse response) {
        List<String> postingIds = new ArrayList<>();
        for (JsonNode posting : response.at("/content")) {
            postingIds.add(posting.get("postingId").asString());
        }
        return postingIds;
    }
}
