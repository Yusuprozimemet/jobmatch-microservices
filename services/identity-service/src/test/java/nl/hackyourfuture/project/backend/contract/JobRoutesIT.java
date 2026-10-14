package nl.hackyourfuture.project.backend.contract;

import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which {@code /api/jobs} routes a logged-out visitor may reach.
 *
 * <p>Browsing jobs without an account is the product's front door; ranking them against a
 * profile is not. Phase 2 moves this decision out of {@code SecurityConfig} and into the
 * gateway, and Phase 3 splits the routes across two services — the answers below have to come
 * out the same after both.
 */
class JobRoutesIT extends IntegrationTest {

    @Test
    void searchIsPublic() {
        assertThat(anonymous().get("/api/jobs").status()).isEqualTo(200);
    }

    @Test
    void filtersArePublic() {
        assertThat(anonymous().get("/api/jobs/filters").status()).isEqualTo(200);
    }

    @Test
    void oneJobIsPublic() {
        assertThat(anonymous().get("/api/jobs/seed-0001").status()).isEqualTo(200);
    }

    /**
     * The route that must not be public — and the one most likely to become public by
     * accident. {@code /api/jobs/top-matches} also matches the {@code /api/jobs/*} rule that
     * makes job detail public; only the order of the two rules keeps it private. Reordering
     * them looks harmless and would hand every visitor a stranger's ranked jobs.
     */
    @Test
    void topMatchesRequiresLogin() {
        assertThat(anonymous().get("/api/jobs/top-matches").status()).isEqualTo(401);
    }

    // Authenticated is enough to get past the gate. What it answers with - a ranking, or a
    // 422 for a user with no profile - is Day 04's subject, not this file's.
    @Test
    void topMatchesLetsALoggedInUserThrough() {
        TestUser user = aUser().create();

        assertThat(authenticatedAs(user).get("/api/jobs/top-matches").status()).isNotEqualTo(401);
    }

    // A made-up id under a public prefix stays public: a 404, not a login prompt.
    @Test
    void anUnknownJobIsANotFoundRatherThanALoginPrompt() {
        assertThat(anonymous().get("/api/jobs/no-such-posting").status()).isEqualTo(404);
    }
}
