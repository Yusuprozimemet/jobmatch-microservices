package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET/PUT /api/profile} — the wire contract.
 *
 * <p>The profile is the input to matching, so what a save stores and a read returns is a
 * contract two services will later depend on. Nothing here knows that it is one row keyed by
 * user id, or that the caller is resolved from an email: Day 10 stops resolving the email and
 * Day 28 moves the endpoint into identity-service, and neither should touch this file.
 */
class ProfileIT extends IntegrationTest {

    @Test
    void returnsAnEmptyProfileForAUserWhoHasNeverSaved() {
        TestUser user = aUser().create();

        ApiResponse response = authenticatedAs(user).get("/api/profile");

        // An empty profile, not a 404 - the frontend renders the same form either way.
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/userId").asString()).isEqualTo(user.id().toString());
        assertThat(stringsAt(response, "/skills")).isEmpty();
        // isNull() rather than a missing check: a field that vanished from the response
        // would be a MissingNode here and fail, which is the point.
        assertThat(response.at("/category").isNull()).isTrue();
        assertThat(response.at("/preferredCity").isNull()).isTrue();
        assertThat(response.at("/workMode").isNull()).isTrue();
        assertThat(response.at("/experienceLevel").isNull()).isTrue();
        assertThat(response.at("/employmentType").isNull()).isTrue();
        assertThat(response.at("/salaryPreference").isNull()).isTrue();
    }

    @Test
    void savesAndReadsBackTheWholeProfile() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);

        ApiResponse saved = client.put("/api/profile", aProfileBody());

        assertThat(saved.status()).isEqualTo(200);
        assertProfileMatchesTheBody(saved, user);
        assertProfileMatchesTheBody(client.get("/api/profile"), user);
    }

    // The save answers with the profile as stored, so the frontend does not re-read it.
    @Test
    void theSaveRespondsWithTheSameProfileTheNextReadReturns() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);

        ApiResponse saved = client.put("/api/profile", aProfileBody());
        ApiResponse read = client.get("/api/profile");

        assertThat(saved.json()).isEqualTo(read.json());
    }

    // Salary comes back at the scale it is stored at, not as the caller happened to send it.
    // decimalValue() rather than asString(): asString() coerces the number through a double
    // and quietly turns 45000.00 into "45000.0", which would hide the scale this pins.
    @Test
    void returnsTheSalaryWithTwoDecimals() {
        TestUser user = aUser().create();
        Map<String, Object> body = aProfileBody();
        body.put("salaryPreference", new BigDecimal("45000"));

        ApiResponse response = authenticatedAs(user).put("/api/profile", body);

        assertThat(response.body()).contains("\"salaryPreference\":45000.00");
        assertThat(response.at("/salaryPreference").decimalValue()).isEqualTo(new BigDecimal("45000.00"));
    }

    // PUT replaces the profile. Leaving a field out is the only way a user can empty
    // something they filled in before, so it has to clear rather than keep.
    @Test
    void aFieldLeftOutOfTheSaveIsCleared() {
        TestUser user = aUser().create();
        ApiClient client = authenticatedAs(user);
        client.put("/api/profile", aProfileBody());

        Map<String, Object> withoutCity = aProfileBody();
        withoutCity.remove("preferredCity");
        ApiResponse response = client.put("/api/profile", withoutCity);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/preferredCity").isNull()).isTrue();
        // The fields that were sent are untouched.
        assertThat(response.at("/category").asString()).isEqualTo("software_engineering");
    }

    @Test
    void aFieldSentAsBlankIsStoredAsNull() {
        TestUser user = aUser().create();
        Map<String, Object> body = aProfileBody();
        body.put("workMode", "   ");

        ApiResponse response = authenticatedAs(user).put("/api/profile", body);

        assertThat(response.at("/workMode").isNull()).isTrue();
    }

    @Test
    void trimsSurroundingWhitespaceFromText() {
        TestUser user = aUser().create();
        Map<String, Object> body = aProfileBody();
        body.put("preferredCity", "  Utrecht  ");
        body.put("skills", List.of("  Java  ", "SQL", "Docker", "React", "TypeScript"));

        ApiResponse response = authenticatedAs(user).put("/api/profile", body);

        assertThat(response.at("/preferredCity").asString()).isEqualTo("Utrecht");
        assertThat(stringsAt(response, "/skills")).first().isEqualTo("Java");
    }

    // Self-service only: two accounts saving at the same time must not see each other's answers.
    @Test
    void eachUserReadsOnlyTheirOwnProfile() {
        TestUser first = aUser().create();
        TestUser second = aUser().create();
        aProfile().forUser(first).preferredCity("Rotterdam").create();
        aProfile().forUser(second).preferredCity("Groningen").create();

        ApiResponse firstProfile = authenticatedAs(first).get("/api/profile");
        ApiResponse secondProfile = authenticatedAs(second).get("/api/profile");

        assertThat(firstProfile.at("/userId").asString()).isEqualTo(first.id().toString());
        assertThat(firstProfile.at("/preferredCity").asString()).isEqualTo("Rotterdam");
        assertThat(secondProfile.at("/userId").asString()).isEqualTo(second.id().toString());
        assertThat(secondProfile.at("/preferredCity").asString()).isEqualTo("Groningen");
    }

    // The body carries no account, so naming one cannot redirect the save. Worth pinning:
    // Day 10 starts passing a user id down from the controller, and it has to be the
    // authenticated one rather than a field the caller controls.
    @Test
    void aUserIdInTheBodyCannotRedirectTheSave() {
        TestUser caller = aUser().create();
        TestUser victim = aUser().create();
        aProfile().forUser(victim).preferredCity("Eindhoven").create();

        Map<String, Object> body = aProfileBody();
        body.put("userId", victim.id().toString());
        ApiResponse response = authenticatedAs(caller).put("/api/profile", body);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/userId").asString()).isEqualTo(caller.id().toString());
        assertThat(authenticatedAs(victim).get("/api/profile").at("/preferredCity").asString())
                .isEqualTo("Eindhoven");
    }

    @Test
    void readingAProfileWithoutLoggingInReturns401() {
        assertThat(anonymous().get("/api/profile").status()).isEqualTo(401);
    }

    @Test
    void savingAProfileWithoutLoggingInReturns401() {
        assertThat(anonymous().put("/api/profile", aProfileBody()).status()).isEqualTo(401);
    }

    private static void assertProfileMatchesTheBody(ApiResponse response, TestUser user) {
        assertThat(response.at("/userId").asString()).isEqualTo(user.id().toString());
        assertThat(stringsAt(response, "/skills"))
                .containsExactly("Java", "SQL", "Docker", "React", "TypeScript");
        assertThat(response.at("/category").asString()).isEqualTo("software_engineering");
        assertThat(response.at("/preferredCity").asString()).isEqualTo("Amsterdam");
        assertThat(response.at("/workMode").asString()).isEqualTo("hybrid");
        assertThat(response.at("/experienceLevel").asString()).isEqualTo("medior");
        assertThat(response.at("/employmentType").asString()).isEqualTo("full_time");
        assertThat(response.at("/salaryPreference").decimalValue()).isEqualTo(new BigDecimal("45000.00"));
    }

    private static List<String> stringsAt(ApiResponse response, String jsonPointer) {
        List<String> values = new ArrayList<>();
        for (JsonNode element : response.at(jsonPointer)) {
            values.add(element.asString());
        }
        return values;
    }

    // A LinkedHashMap rather than Map.of: tests remove keys from it, and a field cleared by
    // being left out is half the contract here.
    private static Map<String, Object> aProfileBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skills", List.of("Java", "SQL", "Docker", "React", "TypeScript"));
        body.put("category", "software_engineering");
        body.put("preferredCity", "Amsterdam");
        body.put("workMode", "hybrid");
        body.put("experienceLevel", "medior");
        body.put("employmentType", "full_time");
        body.put("salaryPreference", new BigDecimal("45000.00"));
        return body;
    }
}
