package nl.hackyourfuture.project.backend.matching;

import nl.hackyourfuture.project.backend.identity.token.AccessTokens;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.ApiResponse;
import nl.hackyourfuture.project.backend.support.MatchingTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 41 Track C: top-matches trusts the token's {@code sub} for the user id, not an email
 * lookup.
 *
 * <p>The token's {@code sub} is user A, who has a profile; its email is user B's, who has none.
 * Before Day 41 this was 422: {@code CurrentUserIdResolver} found B by email.
 */
class TopMatchesTrustTheSubjectIT extends MatchingTest {

    @Autowired
    private AccessTokens accessTokens;

    @Test
    void theIdIsTheTokensSubNotAnEmailLookup() {
        TestUser userA = userWithProfile();
        TestUser userB = aUser().create();
        posting("subject-based-1", "Subject-Based Job", "java", "sql");

        String tokenForAEmailB = accessTokens.mint(userA.id(), userB.email());
        ApiClient client = anonymous().withCookie("access_token", tokenForAEmailB);
        model().willScoreInPromptOrder(80);
        ApiResponse response = client.get("/api/jobs/top-matches");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("subject-based-1");
    }
}
