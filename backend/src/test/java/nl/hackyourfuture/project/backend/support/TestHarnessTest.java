package nl.hackyourfuture.project.backend.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The harness testing itself.
 *
 * <p>Days 2-4 write the tests that matter; these only prove that the ground they stand on
 * holds, so that a broken fixture shows up here instead of as twenty confusing failures.
 * {@link SecondHarnessTest} is the other half: the two together are what shows the container
 * and the clean-slate reset work <em>across</em> test classes.
 */
class TestHarnessTest extends IntegrationTest {

    @Test
    void startsOneContainerForTheWholeRun() {
        assertThat(ObservedContainers.record()).hasSize(1);
    }

    @Test
    void seedsTheMartWithPostingsCitiesAndSkills() {
        long postings = count("SELECT count(*) FROM analytics.fct_postings");
        long withCities = count("SELECT count(DISTINCT posting_id) FROM analytics.fct_postings_cities");
        long withSkills = count("SELECT count(DISTINCT posting_id) FROM analytics.fct_postings_skills");
        long withDates = count("SELECT count(*) FROM analytics.fct_postings WHERE posted_date IS NOT NULL");

        assertThat(postings).isGreaterThanOrEqualTo(20);
        assertThat(withCities).isEqualTo(postings);
        assertThat(withDates).isEqualTo(postings);
        // Every posting but seed-0023, which exists to be the one with an empty skills array.
        assertThat(withSkills).isEqualTo(postings - 1);
    }

    @Test
    void seedsPostingsAcrossCitiesCategoriesAndWorkModes() {
        assertThat(distinct("SELECT DISTINCT city FROM analytics.fct_postings_cities"))
                .contains("amsterdam", "rotterdam", "utrecht", "eindhoven");
        assertThat(distinct("SELECT DISTINCT category FROM analytics.fct_postings"))
                .contains("software_engineering", "data_engineering", "devops");
        assertThat(distinct("SELECT DISTINCT work_mode FROM analytics.fct_postings"))
                .containsExactlyInAnyOrder("onsite", "hybrid", "remote");
    }

    @Test
    void authenticatedAsReachesAnAuthenticatedEndpoint() {
        TestUser user = aUser().name("Ada Lovelace").create();

        ApiResponse response = authenticatedAs(user).get("/api/users/me");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/email").asString()).isEqualTo(user.email());
        assertThat(response.at("/name").asString()).isEqualTo("Ada Lovelace");
    }

    @Test
    void anonymousIsRejectedByAnAuthenticatedEndpoint() {
        assertThat(anonymous().get("/api/users/me").status()).isEqualTo(401);
    }

    @Test
    void buildsAPostingTheApplicationCanRead() {
        TestPosting posting = aPosting()
                .title("Rust Engineer")
                .company("Oxide")
                .cities("delft")
                .skills("rust", "linux")
                .create();

        ApiResponse response = anonymous().get("/api/jobs/{id}", posting.id());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/title").asString()).isEqualTo("Rust Engineer");
        assertThat(response.at("/companyName").asString()).isEqualTo("Oxide");
        assertThat(response.at("/location").asString()).isEqualTo("Delft");
        assertThat(response.at("/skills").toString()).isEqualTo("[\"linux\",\"rust\"]");
    }

    @Test
    void buildsAUserAndProfileTheApplicationCanRead() {
        TestUser user = aUser().create();
        aProfile().forUser(user).category("data_engineering").skills("python", "dbt").create();

        ApiResponse response = authenticatedAs(user).get("/api/profile");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/category").asString()).isEqualTo("data_engineering");
        assertThat(response.at("/skills").toString()).isEqualTo("[\"python\",\"dbt\"]");
    }

    // This test and the next one both start by creating users and both assert the schema was
    // empty when they began. They can only pass together if the reset runs between them,
    // whichever order JUnit picks.
    @Test
    void startsFromACleanAppSchema() {
        assertThat(count("SELECT count(*) FROM users")).isZero();
        aUser().create();
        aUser().create();
    }

    @Test
    void startsFromACleanAppSchemaEveryTime() {
        assertThat(count("SELECT count(*) FROM users")).isZero();
        aUser().create();
    }

    private long count(String sql) {
        return jdbc().sql(sql).query(Long.class).single();
    }

    private java.util.List<String> distinct(String sql) {
        return jdbc().sql(sql).query(String.class).list();
    }
}
