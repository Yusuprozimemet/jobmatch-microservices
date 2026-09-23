package nl.hackyourfuture.project.backend.tokens;

import com.nimbusds.jose.util.Base64URL;
import nl.hackyourfuture.project.backend.identity.token.AccessTokens;
import nl.hackyourfuture.project.backend.identity.token.SigningKey;
import nl.hackyourfuture.project.backend.support.ApiClient;
import nl.hackyourfuture.project.backend.support.Cookies;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestTokens;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A browser still holding an auth cookie that no longer authenticates loses nothing on a route
 * anyone may use, and gets a clean 401 where it must be signed in (Day 13, Track 0).
 *
 * <p>Written to hold before and after the switch to tokens. Today the cookie is a session id the
 * server does not know; from Day 13's Track A it is an access token the resource server rejects,
 * and it must not reject the request with it. Not a contract test: it puts a cookie in the jar
 * that no server set, which a contract test has no business doing.
 */
class StaleCookieIT extends IntegrationTest {

    @Autowired
    private SigningKey signingKey;

    @Autowired
    private AccessTokens accessTokens;

    @ParameterizedTest
    @ValueSource(strings = {"expired", "tampered", "garbage"})
    void aStaleCookieStillSeesTheJobs(String kind) {
        assertThat(holding(kind).get("/api/jobs").status()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "tampered", "garbage"})
    void aStaleCookieStillLogsIn(String kind) {
        TestUser user = aUser().create();

        assertThat(holding(kind).post("/api/auth/login", Map.of("email", user.email(), "password", user.password()))
                .status())
                .isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "tampered", "garbage"})
    void aStaleCookieStillRegisters(String kind) {
        assertThat(holding(kind).post("/api/auth/register", Map.of(
                        "name", "Stale Cookie", "email", "stale-" + kind + "@example.test",
                        "password", "Password123!", "acceptedTerms", true))
                .status())
                .isEqualTo(201);
    }

    @Test
    void aTamperedCookieIsRefusedNotAFailure() {
        assertThat(holding("tampered").get("/api/users/me").status()).isEqualTo(401);
    }

    private ApiClient holding(String kind) {
        String value = switch (kind) {
            case "expired" -> TestTokens.expired(signingKey, UUID.randomUUID(), "someone@example.test");
            case "tampered" -> tampered(accessTokens.mint(UUID.randomUUID(), "someone@example.test"));
            case "garbage" -> "not-a-token-or-a-session";
            default -> throw new IllegalArgumentException(kind);
        };
        return anonymous().withCookie(Cookies.AUTH, value);
    }

    /** A real token with one byte of its signature changed. */
    private static String tampered(String token) {
        String[] parts = token.split("\\.");
        byte[] signature = new Base64URL(parts[2]).decode();
        signature[signature.length / 2] ^= 0x01;
        return parts[0] + "." + parts[1] + "." + Base64URL.encode(signature);
    }
}
