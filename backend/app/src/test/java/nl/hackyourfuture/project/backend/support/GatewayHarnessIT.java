package nl.hackyourfuture.project.backend.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code -Dharness.gateway=true}, a test's client talks to the gateway, and a login made
 * through it works (Day 15). Without this, a run that silently went direct would pass as one
 * through the gateway. Skipped in the direct run.
 */
@EnabledIfSystemProperty(named = "harness.gateway", matches = "true")
class GatewayHarnessIT extends IntegrationTest {

    @Test
    void theClientTalksToTheGatewayNotTheApplication() {
        TestUser user = aUser().create();

        assertThat(anonymous().baseUrl()).isNotEqualTo(direct().baseUrl());
        assertThat(authenticatedAs(user).baseUrl()).isNotEqualTo(direct().baseUrl());
        assertThat(authenticatedAs(user).get("/api/users/me").status()).isEqualTo(200);
    }
}
