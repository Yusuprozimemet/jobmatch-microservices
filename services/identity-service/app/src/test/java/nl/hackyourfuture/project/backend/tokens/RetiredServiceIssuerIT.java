package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * identity serves no service key set (Day 42, C42.4): no service verifies its tokens.
 */
class RetiredServiceIssuerIT extends IntegrationTest {

    @Test
    void theServiceKeySetIsGone() {
        assertThat(direct().get("/.well-known/service-jwks.json").status())
                .as("no permit is left for the path; anyRequest().authenticated() answers")
                .isEqualTo(401);
    }
}
