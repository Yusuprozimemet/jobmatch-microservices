package nl.hackyourfuture.project.backend.postings;

import nl.hackyourfuture.project.backend.shared.internal.ServiceToken;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.ShortlistFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The match shortlist's order, pinned over HTTP at {@code POST /internal/postings/shortlist},
 * wherever the route is served. Held to the same {@link ShortlistFixture}.
 * Day 09's Notes found the order pinned by 1 test of 22.
 */
class ShortlistOrderIT extends IntegrationTest {

    @Autowired
    private ServiceToken serviceToken;

    @Test
    void ranksByMatchesThenDateThenIdOnePerRepostInTheCityUpToTheLimit() {
        ShortlistFixture.create(jdbc());

        var response = inNetwork()
                .withHeader("Authorization", "Bearer " + serviceToken.mint())
                .post("/internal/postings/shortlist",
                        Map.of("city", ShortlistFixture.CITY, "skills", ShortlistFixture.SKILLS, "limit", ShortlistFixture.LIMIT));

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        List<String> result = new ArrayList<>();
        for (JsonNode item : body) {
            result.add(item.at("/postingId").asText());
        }
        assertThat(result).containsExactlyElementsOf(ShortlistFixture.EXPECTED);
    }
}
