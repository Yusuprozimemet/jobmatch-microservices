package nl.hackyourfuture.project.backend.tokens;

import nl.hackyourfuture.project.backend.identity.token.RefreshTokens;
import nl.hackyourfuture.project.backend.identity.user.User;
import nl.hackyourfuture.project.backend.support.IntegrationTest;
import nl.hackyourfuture.project.backend.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A refresh token is stored only as its hash, redeems for its user while it is live, and goes
 * with the account (Day 12).
 *
 * <p>Not a contract test: it calls the bean, because nothing issues or redeems a refresh token
 * over HTTP until Day 13. What it stored is read back through the harness's own connection.
 */
class RefreshTokensIT extends IntegrationTest {

    @Autowired
    private RefreshTokens refreshTokens;

    @Test
    void isStoredOnlyAsItsSha256AndLastsThirtyDays() throws Exception {
        TestUser user = aUser().create();
        String token = refreshTokens.issue(user.id());

        Map<String, Object> row = jdbc().sql("SELECT * FROM identity.refresh_tokens").query().singleRow();
        assertThat(row.values()).allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain(token));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        assertThat(row.get("token_hash")).isEqualTo(HexFormat.of().formatHex(digest));
        assertThat(jdbc().sql("SELECT extract(epoch FROM expires_at - created_at)::bigint FROM identity.refresh_tokens")
                .query(Long.class)
                .single())
                .isEqualTo(Duration.ofDays(30).toSeconds());
    }

    @Test
    void redeemsForItsUserWhileLive() {
        TestUser user = aUser().create();
        String token = refreshTokens.issue(user.id());

        assertThat(refreshTokens.redeem(token)).map(User::getId).contains(user.id());
    }

    @Test
    void redeemsForNobodyOnceRevoked() {
        TestUser user = aUser().create();
        String token = refreshTokens.issue(user.id());

        refreshTokens.revoke(token);

        assertThat(refreshTokens.redeem(token)).isEmpty();
    }

    @Test
    void redeemsForNobodyOnceExpired() {
        TestUser user = aUser().create();
        String token = refreshTokens.issue(user.id());
        jdbc().sql("UPDATE identity.refresh_tokens SET expires_at = now() - interval '1 second'").update();

        assertThat(refreshTokens.redeem(token)).isEmpty();
    }

    @Test
    void redeemsForNobodyWhenUnknown() {
        aUser().create();

        assertThat(refreshTokens.redeem("not-a-token-anyone-was-given")).isEmpty();
    }

    @Test
    void goesWithTheAccount() {
        TestUser user = aUser().create();
        refreshTokens.issue(user.id());
        refreshTokens.issue(user.id());

        jdbc().sql("DELETE FROM identity.users WHERE id = :id").param("id", user.id()).update();

        assertThat(jdbc().sql("SELECT count(*) FROM identity.refresh_tokens").query(Long.class).single()).isZero();
    }
}
