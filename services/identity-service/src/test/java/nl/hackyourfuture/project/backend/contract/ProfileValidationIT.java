package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code PUT /api/profile} — what it refuses, and what it cleans up before storing.
 *
 * <p>Matching ranks on these skills, so the count that survives normalisation is the real
 * constraint, not the count that was sent. Both halves are pinned here: the rejection and the
 * cleaning.
 *
 * <p>The bounds are written out as numbers rather than imported from
 * {@code UpdateProfileRequest}. Five-to-twenty is the promise made to the caller; a constant
 * that moves into identity-service is the mechanism behind it, and a test that imports it
 * would go green if someone changed the promise.
 */
class ProfileValidationIT extends IntegrationTest {

    private static final int MIN_SKILLS = 5;
    private static final int MAX_SKILLS = 20;

    @Test
    void rejectsFewerSkillsThanTheMinimum() {
        ApiResponse response = save(distinctSkills(MIN_SKILLS - 1));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/skills").asString()).isEqualTo("Select between 5 and 20 skills");
    }

    @Test
    void rejectsMoreSkillsThanTheMaximum() {
        ApiResponse response = save(distinctSkills(MAX_SKILLS + 1));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/skills").asString()).isEqualTo("Select between 5 and 20 skills");
    }

    @Test
    void acceptsExactlyTheMinimumAndExactlyTheMaximum() {
        assertThat(save(distinctSkills(MIN_SKILLS)).status()).isEqualTo(200);
        assertThat(save(distinctSkills(MAX_SKILLS)).status()).isEqualTo(200);
    }

    @Test
    void rejectsASaveWithNoSkillsAtAll() {
        Map<String, Object> body = aProfileBody();
        body.put("skills", null);

        ApiResponse response = save(body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/skills").asString()).isEqualTo("Skills are required");
    }

    // The count is re-checked after cleanup, so five entries that collapse into four are a
    // 400 even though the list that arrived was the right length.
    @Test
    void rejectsWhenDuplicatesDropTheCountBelowTheMinimum() {
        ApiResponse response = save(List.of("Java", "java", "SQL", "Docker", "React"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/detail").asString())
                .contains("After removing blanks and duplicates you have 4");
    }

    @Test
    void rejectsWhenBlankSkillsDropTheCountBelowTheMinimum() {
        ApiResponse response = save(Arrays.asList("Java", "   ", "SQL", "Docker", "React"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/detail").asString())
                .contains("After removing blanks and duplicates you have 4");
    }

    // Collapsed to one, and the first spelling is the one stored: the user picked it, and it
    // is what the profile page shows back to them.
    @Test
    void collapsesSkillsThatDifferOnlyInCaseAndKeepsTheFirstSpelling() {
        ApiResponse response = save(List.of("React", "react", "REACT", "SQL", "Docker", "Java", "Go"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(skills(response)).containsExactly("React", "SQL", "Docker", "Java", "Go");
    }

    @Test
    void treatsHyphensAndSpacesInASkillAsTheSameSkill() {
        ApiResponse response = save(List.of("Node-js", "node js", "Node  JS", "SQL", "Docker", "Java", "Go"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(skills(response)).containsExactly("Node-js", "SQL", "Docker", "Java", "Go");
    }

    @Test
    void rejectsASkillLongerThanAHundredCharacters() {
        List<String> skills = new ArrayList<>(distinctSkills(MIN_SKILLS));
        skills.set(0, "x".repeat(101));

        ApiResponse response = save(skills);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/skills[0]").asString())
                .isEqualTo("A skill may be at most 100 characters");
    }

    // The text columns are 255 wide, so an over-long value is a 400 rather than a 500 from
    // the database.
    @Test
    void rejectsTextLongerThanTheColumnAllows() {
        Map<String, Object> body = aProfileBody();
        body.put("category", "x".repeat(256));

        ApiResponse response = save(body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/category").asString())
                .isEqualTo("Category may be at most 255 characters");
    }

    @Test
    void rejectsANegativeSalary() {
        Map<String, Object> body = aProfileBody();
        body.put("salaryPreference", new BigDecimal("-1"));

        ApiResponse response = save(body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/salaryPreference").asString())
                .isEqualTo("Salary preference cannot be negative");
    }

    @Test
    void rejectsASalaryWiderThanTheColumn() {
        Map<String, Object> body = aProfileBody();
        body.put("salaryPreference", new BigDecimal("123456789"));

        ApiResponse response = save(body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/salaryPreference").asString())
                .isEqualTo("Salary preference may have at most 8 digits and 2 decimals");
    }

    private ApiResponse save(List<String> skills) {
        Map<String, Object> body = aProfileBody();
        body.put("skills", skills);
        return save(body);
    }

    private ApiResponse save(Map<String, Object> body) {
        return authenticatedAs(aUser().create()).put("/api/profile", body);
    }

    private static List<String> skills(ApiResponse response) {
        List<String> values = new ArrayList<>();
        for (JsonNode element : response.at("/skills")) {
            values.add(element.asString());
        }
        return values;
    }

    private static List<String> distinctSkills(int count) {
        return IntStream.range(0, count).mapToObj(index -> "skill-" + index).toList();
    }

    private static Map<String, Object> aProfileBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skills", distinctSkills(MIN_SKILLS));
        body.put("category", "software_engineering");
        body.put("preferredCity", "Amsterdam");
        body.put("workMode", "hybrid");
        body.put("experienceLevel", "medior");
        body.put("employmentType", "full_time");
        body.put("salaryPreference", new BigDecimal("45000.00"));
        return body;
    }
}
