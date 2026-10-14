package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/jobs/filters} — the options behind the search dropdowns.
 *
 * <p>Every value here is read back into {@code GET /api/jobs} as a filter, so the contract is
 * not only which lists come back but that what they contain still works when it is sent
 * again. Two of the five lists have no filter behind them today; that is pinned rather than
 * fixed, and the Day 03 spec records why.
 */
class JobFiltersIT extends IntegrationTest {

    @Test
    void returnsAllFiveOptionLists() {
        ApiResponse response = anonymous().get("/api/jobs/filters");

        assertThat(response.status()).isEqualTo(200);
        assertThat(options(response, "/locations")).isNotEmpty();
        assertThat(options(response, "/categories")).isNotEmpty();
        assertThat(options(response, "/workModes")).isNotEmpty();
        assertThat(options(response, "/experienceLevels")).isNotEmpty();
        assertThat(options(response, "/employmentTypes")).isNotEmpty();
    }

    @Test
    void listsTheCategoriesTheMartHolds() {
        ApiResponse response = anonymous().get("/api/jobs/filters");

        // Snake-cased, as the mart stores them: the value round-trips into the search filter
        // and the frontend formats it for display.
        assertThat(options(response, "/categories")).containsExactlyInAnyOrder(
                "software_engineering", "data_engineering", "data_analytics",
                "data_science", "devops", "design");
    }

    @Test
    void listsTheWorkModesEmploymentTypesAndExperienceLevelsTheMartHolds() {
        ApiResponse response = anonymous().get("/api/jobs/filters");

        assertThat(options(response, "/workModes"))
                .containsExactlyInAnyOrder("onsite", "hybrid", "remote");
        assertThat(options(response, "/employmentTypes"))
                .containsExactlyInAnyOrder("full_time", "part_time", "contract");
        assertThat(options(response, "/experienceLevels"))
                .containsExactlyInAnyOrder("junior", "medior", "senior");
    }

    @Test
    void listsTheCitiesTheMartHoldsAndNoCountries() {
        ApiResponse response = anonymous().get("/api/jobs/filters");

        // "Netherlands" sits in the city bridge on seed-0021 and is not a city, so it is not
        // an option. Utrecht, the same posting's real city, is.
        assertThat(options(response, "/locations")).containsExactly(
                "Amsterdam", "Arnhem", "Delft", "Den Haag", "Eindhoven",
                "Groningen", "Rotterdam", "Utrecht");
    }

    // The point of the endpoint: an option a user picks has to work as a filter. A city is
    // the one that could break, because the list is display-cased and the filter is not.
    @Test
    void everyCityOptionWorksAsASearchFilter() {
        for (String city : options(anonymous().get("/api/jobs/filters"), "/locations")) {
            ApiResponse search = anonymous().get("/api/jobs?size=100&location={city}", city);

            assertThat(search.at("/totalElements").asLong())
                    .as("postings in %s", city)
                    .isPositive();
        }
    }

    @Test
    void isPublic() {
        assertThat(anonymous().get("/api/jobs/filters").status()).isEqualTo(200);
    }

    private static List<String> options(ApiResponse response, String jsonPointer) {
        List<String> values = new ArrayList<>();
        for (JsonNode option : response.at(jsonPointer)) {
            values.add(option.asString());
        }
        return values;
    }
}
